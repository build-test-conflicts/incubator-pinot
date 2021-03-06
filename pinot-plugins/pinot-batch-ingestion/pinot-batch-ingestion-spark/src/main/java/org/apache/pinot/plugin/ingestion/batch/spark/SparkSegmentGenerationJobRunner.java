/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.plugin.ingestion.batch.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.pinot.common.config.TableConfig;
import org.apache.pinot.common.utils.TarGzCompressionUtils;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationTaskRunner;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.filesystem.PinotFS;
import org.apache.pinot.spi.filesystem.PinotFSFactory;
import org.apache.pinot.spi.ingestion.batch.runner.IngestionJobRunner;
import org.apache.pinot.spi.ingestion.batch.spec.Constants;
import org.apache.pinot.spi.ingestion.batch.spec.PinotClusterSpec;
import org.apache.pinot.spi.ingestion.batch.spec.PinotFSSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationJobSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationTaskSpec;
import org.apache.pinot.spi.utils.DataSize;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SparkSegmentGenerationJobRunner implements IngestionJobRunner, Serializable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SparkSegmentGenerationJobRunner.class);
  private static final String OFFLINE = "OFFLINE";
  private static final String DEPS_JAR_DIR = "dependencyJarDir";
  private static final String STAGING_DIR = "stagingDir";

  private SegmentGenerationJobSpec _spec;

  public SparkSegmentGenerationJobRunner() {
  }

  public SparkSegmentGenerationJobRunner(SegmentGenerationJobSpec spec) {
    init(spec);
  }

  private static String generateSchemaURI(String controllerUri, String table) {
    return String.format("%s/tables/%s/schema", controllerUri, table);
  }

  private static String generateTableConfigURI(String controllerUri, String table) {
    return String.format("%s/tables/%s", controllerUri, table);
  }

  /**
   * Generate a relative output directory path when `useRelativePath` flag is on.
   * This method will compute the relative path based on `inputFile` and `baseInputDir`,
   * then apply only the directory part of relative path to `outputDir`.
   * E.g.
   *    baseInputDir = "/path/to/input"
   *    inputFile = "/path/to/input/a/b/c/d.avro"
   *    outputDir = "/path/to/output"
   *    getRelativeOutputPath(baseInputDir, inputFile, outputDir) = /path/to/output/a/b/c
   */
  public static URI getRelativeOutputPath(URI baseInputDir, URI inputFile, URI outputDir) {
    URI relativePath = baseInputDir.relativize(inputFile);
    Preconditions.checkState(relativePath.getPath().length() > 0 && !relativePath.equals(inputFile),
        "Unable to extract out the relative path based on base input path: " + baseInputDir);
    String outputDirStr = outputDir.toString();
    outputDir = !outputDirStr.endsWith("/") ? URI.create(outputDirStr.concat("/")) : outputDir;
    URI relativeOutputURI = outputDir.resolve(relativePath).resolve(".");
    return relativeOutputURI;
  }

  @Override
  public void init(SegmentGenerationJobSpec spec) {
    _spec = spec;
    if (_spec.getInputDirURI() == null) {
      throw new RuntimeException("Missing property 'inputDirURI' in 'jobSpec' file");
    }
    if (_spec.getOutputDirURI() == null) {
      throw new RuntimeException("Missing property 'outputDirURI' in 'jobSpec' file");
    }
    if (_spec.getRecordReaderSpec() == null) {
      throw new RuntimeException("Missing property 'recordReaderSpec' in 'jobSpec' file");
    }
    if (_spec.getTableSpec() == null) {
      throw new RuntimeException("Missing property 'tableSpec' in 'jobSpec' file");
    }
    if (_spec.getTableSpec().getTableName() == null) {
      throw new RuntimeException("Missing property 'tableName' in 'tableSpec'");
    }
    if (_spec.getTableSpec().getSchemaURI() == null) {
      if (_spec.getPinotClusterSpecs() == null || _spec.getPinotClusterSpecs().length == 0) {
        throw new RuntimeException("Missing property 'schemaURI' in 'tableSpec'");
      }
      PinotClusterSpec pinotClusterSpec = _spec.getPinotClusterSpecs()[0];
      String schemaURI = generateSchemaURI(pinotClusterSpec.getControllerURI(), _spec.getTableSpec().getTableName());
      _spec.getTableSpec().setSchemaURI(schemaURI);
    }
    if (_spec.getTableSpec().getTableConfigURI() == null) {
      if (_spec.getPinotClusterSpecs() == null || _spec.getPinotClusterSpecs().length == 0) {
        throw new RuntimeException("Missing property 'tableConfigURI' in 'tableSpec'");
      }
      PinotClusterSpec pinotClusterSpec = _spec.getPinotClusterSpecs()[0];
      String tableConfigURI =
          generateTableConfigURI(pinotClusterSpec.getControllerURI(), _spec.getTableSpec().getTableName());
      _spec.getTableSpec().setTableConfigURI(tableConfigURI);
    }
    if (_spec.getExecutionFrameworkSpec().getExtraConfigs() == null) {
      _spec.getExecutionFrameworkSpec().setExtraConfigs(new HashMap<>());
    }
  }

  @Override
  public void run()
      throws Exception {
    //init all file systems
    List<PinotFSSpec> pinotFSSpecs = _spec.getPinotFSSpecs();
    for (PinotFSSpec pinotFSSpec : pinotFSSpecs) {
      Configuration config = new MapConfiguration(pinotFSSpec.getConfigs());
      PinotFSFactory.register(pinotFSSpec.getScheme(), pinotFSSpec.getClassName(), config);
    }

    //Get pinotFS for input
    URI inputDirURI = new URI(_spec.getInputDirURI());
    if (inputDirURI.getScheme() == null) {
      inputDirURI = new File(_spec.getInputDirURI()).toURI();
    }
    PinotFS inputDirFS = PinotFSFactory.create(inputDirURI.getScheme());

    //Get outputFS for writing output pinot segments
    URI outputDirURI = new URI(_spec.getOutputDirURI());
    if (outputDirURI.getScheme() == null) {
      outputDirURI = new File(_spec.getOutputDirURI()).toURI();
    }
    PinotFS outputDirFS = PinotFSFactory.create(outputDirURI.getScheme());
    outputDirFS.mkdir(outputDirURI);

    //Get staging directory for temporary output pinot segments
    String stagingDir = _spec.getExecutionFrameworkSpec().getExtraConfigs().get(STAGING_DIR);
    URI stagingDirURI = null;
    if (stagingDir != null) {
      stagingDirURI = URI.create(stagingDir);
      if (stagingDirURI.getScheme() == null) {
        stagingDirURI = new File(stagingDir).toURI();
      }
      if (!outputDirURI.getScheme().equals(stagingDirURI.getScheme())) {
        throw new RuntimeException(String
            .format("The scheme of staging directory URI [%s] and output directory URI [%s] has to be same.",
                stagingDirURI, outputDirURI));
      }
      outputDirFS.mkdir(stagingDirURI);
    }
    //Get list of files to process
    String[] files = inputDirFS.listFiles(inputDirURI, true);

    //TODO: sort input files based on creation time
    List<String> filteredFiles = new ArrayList<>();
    PathMatcher includeFilePathMatcher = null;
    if (_spec.getIncludeFileNamePattern() != null) {
      includeFilePathMatcher = FileSystems.getDefault().getPathMatcher(_spec.getIncludeFileNamePattern());
    }
    PathMatcher excludeFilePathMatcher = null;
    if (_spec.getExcludeFileNamePattern() != null) {
      excludeFilePathMatcher = FileSystems.getDefault().getPathMatcher(_spec.getExcludeFileNamePattern());
    }

    for (String file : files) {
      if (includeFilePathMatcher != null) {
        if (!includeFilePathMatcher.matches(Paths.get(file))) {
          continue;
        }
      }
      if (excludeFilePathMatcher != null) {
        if (excludeFilePathMatcher.matches(Paths.get(file))) {
          continue;
        }
      }
      if (!inputDirFS.isDirectory(new URI(file))) {
        filteredFiles.add(file);
      }
    }

    try {
      JavaSparkContext sparkContext = JavaSparkContext.fromSparkContext(SparkContext.getOrCreate());
      if (_spec.getExecutionFrameworkSpec().getExtraConfigs().containsKey(DEPS_JAR_DIR)) {
        addDepsJarToDistributedCache(sparkContext,
            _spec.getExecutionFrameworkSpec().getExtraConfigs().get(DEPS_JAR_DIR));
      }
      List<String> pathAndIdxList = new ArrayList<>();
      for (int i = 0; i < filteredFiles.size(); i++) {
        pathAndIdxList.add(String.format("%s %d", filteredFiles.get(i), i));
      }
      JavaRDD<String> pathRDD = sparkContext.parallelize(pathAndIdxList, pathAndIdxList.size());

      final URI finalInputDirURI = inputDirURI;
      final URI finalOutputDirURI = (stagingDirURI == null) ? outputDirURI : stagingDirURI;
      pathRDD.foreach(pathAndIdx -> {
        String[] splits = pathAndIdx.split(" ");
        String path = splits[0];
        int idx = Integer.valueOf(splits[1]);
        URI inputFileURI = URI.create(path);
        if (inputFileURI.getScheme() == null) {
          inputFileURI =
              new URI(finalInputDirURI.getScheme(), inputFileURI.getSchemeSpecificPart(), inputFileURI.getFragment());
        }

        //create localTempDir for input and output
        File localTempDir = new File(FileUtils.getTempDirectory(), "pinot-" + System.currentTimeMillis());
        File localInputTempDir = new File(localTempDir, "input");
        FileUtils.forceMkdir(localInputTempDir);
        File localOutputTempDir = new File(localTempDir, "output");
        FileUtils.forceMkdir(localOutputTempDir);

        //copy input path to local
        File localInputDataFile = new File(localInputTempDir, new File(inputFileURI).getName());
        PinotFSFactory.create(inputFileURI.getScheme()).copyToLocalFile(inputFileURI, localInputDataFile);

        //create task spec
        SegmentGenerationTaskSpec taskSpec = new SegmentGenerationTaskSpec();
        taskSpec.setInputFilePath(localInputDataFile.getAbsolutePath());
        taskSpec.setOutputDirectoryPath(localOutputTempDir.getAbsolutePath());
        taskSpec.setRecordReaderSpec(_spec.getRecordReaderSpec());
        taskSpec.setSchema(getSchema());
        taskSpec.setTableConfig(getTableConfig().toJsonNode());
        taskSpec.setSequenceId(idx);
        taskSpec.setSegmentNameGeneratorSpec(_spec.getSegmentNameGeneratorSpec());

        SegmentGenerationTaskRunner taskRunner = new SegmentGenerationTaskRunner(taskSpec);
        String segmentName = taskRunner.run();

        // Tar segment directory to compress file
        File localSegmentDir = new File(localOutputTempDir, segmentName);
        String segmentTarFileName = segmentName + Constants.TAR_GZ_FILE_EXT;
        File localSegmentTarFile = new File(localOutputTempDir, segmentTarFileName);
        LOGGER.info("Tarring segment from: {} to: {}", localSegmentDir, localSegmentTarFile);
        TarGzCompressionUtils.createTarGzOfDirectory(localSegmentDir.getPath(), localSegmentTarFile.getPath());
        long uncompressedSegmentSize = FileUtils.sizeOf(localSegmentDir);
        long compressedSegmentSize = FileUtils.sizeOf(localSegmentTarFile);
        LOGGER.info("Size for segment: {}, uncompressed: {}, compressed: {}", segmentName,
            DataSize.fromBytes(uncompressedSegmentSize), DataSize.fromBytes(compressedSegmentSize));
        //move segment to output PinotFS
        URI outputSegmentTarURI =
            getRelativeOutputPath(finalInputDirURI, inputFileURI, finalOutputDirURI).resolve(segmentTarFileName);
        LOGGER.info("Trying to move segment tar file from: [{}] to [{}]", localSegmentTarFile, outputSegmentTarURI);
        if (!_spec.isOverwriteOutput() && PinotFSFactory.create(outputSegmentTarURI.getScheme())
            .exists(outputSegmentTarURI)) {
          LOGGER.warn("Not overwrite existing output segment tar file: {}", outputDirFS.exists(outputSegmentTarURI));
        } else {
          outputDirFS.copyFromLocalFile(localSegmentTarFile, outputSegmentTarURI);
        }
        FileUtils.deleteQuietly(localSegmentDir);
        FileUtils.deleteQuietly(localSegmentTarFile);
        FileUtils.deleteQuietly(localInputDataFile);
      });
      if (stagingDirURI != null) {
        LOGGER.info("Trying to copy segment tars from staging directory: [{}] to output directory [{}]", stagingDirURI,
            outputDirURI);
        outputDirFS.copy(stagingDirURI, outputDirURI);
      }
    } finally {
      if (stagingDirURI != null) {
        LOGGER.info("Trying to clean up staging directory: [{}]", stagingDirURI);
        outputDirFS.delete(stagingDirURI, true);
      }
    }
  }

  private Schema getSchema() {
    URI schemaURI;
    try {
      schemaURI = new URI(_spec.getTableSpec().getSchemaURI());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Schema URI is not valid - '" + _spec.getTableSpec().getSchemaURI() + "'", e);
    }
    String scheme = schemaURI.getScheme();
    String schemaJson;
    if (PinotFSFactory.isSchemeSupported(scheme)) {
      // Try to use PinotFS to read schema URI
      PinotFS pinotFS = PinotFSFactory.create(scheme);
      InputStream schemaStream;
      try {
        schemaStream = pinotFS.open(schemaURI);
      } catch (IOException e) {
        throw new RuntimeException("Failed to fetch schema from PinotFS - '" + schemaURI + "'", e);
      }
      try {
        schemaJson = IOUtils.toString(schemaStream, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException("Failed to read from schema file data stream on Pinot fs - '" + schemaURI + "'", e);
      }
    } else {
      // Try to directly read from URI.
      try {
        schemaJson = IOUtils.toString(schemaURI, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException("Failed to read from Schema URI - '" + schemaURI + "'", e);
      }
    }
    try {
      return Schema.fromString(schemaJson);
    } catch (IOException e) {
      throw new RuntimeException("Failed to decode Pinot schema from json string - '" + schemaJson + "'", e);
    }
  }

  private TableConfig getTableConfig() {
    URI tableConfigURI;
    try {
      tableConfigURI = new URI(_spec.getTableSpec().getTableConfigURI());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Table config URI is not valid - '" + _spec.getTableSpec().getTableConfigURI() + "'",
          e);
    }
    String scheme = tableConfigURI.getScheme();
    String tableConfigJson;
    if (PinotFSFactory.isSchemeSupported(scheme)) {
      // Try to use PinotFS to read table config URI
      PinotFS pinotFS = PinotFSFactory.create(scheme);
      try {
        tableConfigJson = IOUtils.toString(pinotFS.open(tableConfigURI), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException("Failed to open table config file stream on Pinot fs - '" + tableConfigURI + "'", e);
      }
    } else {
      try {
        tableConfigJson = IOUtils.toString(tableConfigURI, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(
            "Failed to read from table config file data stream on Pinot fs - '" + tableConfigURI + "'", e);
      }
    }
    // Controller API returns a wrapper of table config.
    JsonNode tableJsonNode;
    try {
      tableJsonNode = new ObjectMapper().readTree(tableConfigJson);
    } catch (IOException e) {
      throw new RuntimeException("Failed to decode table config into JSON from String - '" + tableConfigJson + "'", e);
    }
    if (tableJsonNode.has(OFFLINE)) {
      tableJsonNode = tableJsonNode.get(OFFLINE);
    }
    try {
      return TableConfig.fromJsonConfig(tableJsonNode);
    } catch (IOException e) {
      throw new RuntimeException("Failed to decode table config from JSON - '" + tableJsonNode + "'", e);
    }
  }

  protected void addDepsJarToDistributedCache(JavaSparkContext sparkContext, String depsJarDir)
      throws IOException {
    if (depsJarDir != null) {
      URI depsJarDirURI = URI.create(depsJarDir);
      if (depsJarDirURI.getScheme() == null) {
        depsJarDirURI = new File(depsJarDir).toURI();
      }
      PinotFS pinotFS = PinotFSFactory.create(depsJarDirURI.getScheme());
      String[] files = pinotFS.listFiles(depsJarDirURI, true);
      for (String file : files) {
        if (!pinotFS.isDirectory(URI.create(file))) {
          if (file.endsWith(".jar")) {
            LOGGER.info("Adding deps jar: {} to distributed cache", file);
            sparkContext.addJar(file);
          }
        }
      }
    }
  }
}
