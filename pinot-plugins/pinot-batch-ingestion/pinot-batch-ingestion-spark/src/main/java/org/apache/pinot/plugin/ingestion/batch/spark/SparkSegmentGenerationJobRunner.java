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

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.utils.TarGzCompressionUtils;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationTaskRunner;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationUtils;
import org.apache.pinot.spi.filesystem.PinotFS;
import org.apache.pinot.spi.filesystem.PinotFSFactory;
import org.apache.pinot.spi.ingestion.batch.runner.IngestionJobRunner;
import org.apache.pinot.spi.ingestion.batch.spec.Constants;
import org.apache.pinot.spi.ingestion.batch.spec.PinotClusterSpec;
import org.apache.pinot.spi.ingestion.batch.spec.PinotFSSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationJobSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationTaskSpec;
import org.apache.pinot.spi.plugin.PluginManager;
import org.apache.pinot.spi.utils.DataSize;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationUtils.PINOT_PLUGINS_DIR;
import static org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationUtils.PINOT_PLUGINS_TAR_GZ;
import static org.apache.pinot.spi.plugin.PluginManager.PLUGINS_DIR_PROPERTY_NAME;
import static org.apache.pinot.spi.plugin.PluginManager.PLUGINS_INCLUDE_PROPERTY_NAME;


public class SparkSegmentGenerationJobRunner implements IngestionJobRunner, Serializable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SparkSegmentGenerationJobRunner.class);
  private static final String DEPS_JAR_DIR = "dependencyJarDir";
  private static final String STAGING_DIR = "stagingDir";

  private SegmentGenerationJobSpec _spec;

  public SparkSegmentGenerationJobRunner() {
  }

  public SparkSegmentGenerationJobRunner(SegmentGenerationJobSpec spec) {
    init(spec);
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
      String schemaURI = SegmentGenerationUtils
          .generateSchemaURI(pinotClusterSpec.getControllerURI(), _spec.getTableSpec().getTableName());
      _spec.getTableSpec().setSchemaURI(schemaURI);
    }
    if (_spec.getTableSpec().getTableConfigURI() == null) {
      if (_spec.getPinotClusterSpecs() == null || _spec.getPinotClusterSpecs().length == 0) {
        throw new RuntimeException("Missing property 'tableConfigURI' in 'tableSpec'");
      }
      PinotClusterSpec pinotClusterSpec = _spec.getPinotClusterSpecs()[0];
      String tableConfigURI = SegmentGenerationUtils
          .generateTableConfigURI(pinotClusterSpec.getControllerURI(), _spec.getTableSpec().getTableName());
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
      packPluginsToDistributedCache(sparkContext);
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
      final String pluginsIncludes = System.getProperty(PLUGINS_INCLUDE_PROPERTY_NAME);
      pathRDD.foreach(pathAndIdx -> {
        String[] splits = pathAndIdx.split(" ");
        String path = splits[0];
        int idx = Integer.valueOf(splits[1]);

        File pluginsDirFile = new File(PINOT_PLUGINS_DIR + "-" + idx);
        try {
          TarGzCompressionUtils.unTar(new File(PINOT_PLUGINS_TAR_GZ), pluginsDirFile);
        } catch (Exception e) {
          LOGGER.error("Failed to untar pinot plugins tarball", e);
          throw new RuntimeException(e);
        }
        System.setProperty(PLUGINS_DIR_PROPERTY_NAME, pluginsDirFile.toString());
        if (pluginsIncludes != null) {
          System.setProperty(PLUGINS_INCLUDE_PROPERTY_NAME, pluginsIncludes);
        }
        LOGGER.info("Pinot plugins are set at [{}], plugins includes [{}]", pluginsDirFile.getAbsolutePath(),
            pluginsIncludes);

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
        taskSpec.setSchema(SegmentGenerationUtils.getSchema(_spec.getTableSpec().getSchemaURI()));
        taskSpec.setTableConfig(
            SegmentGenerationUtils.getTableConfig(_spec.getTableSpec().getTableConfigURI()).toJsonNode());
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
            SegmentGenerationUtils.getRelativeOutputPath(finalInputDirURI, inputFileURI, finalOutputDirURI)
                .resolve(segmentTarFileName);
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

  protected void packPluginsToDistributedCache(JavaSparkContext sparkContext) {
    String pluginsRootDir = PluginManager.get().getPluginsRootDir();
    File pluginsTarGzFile = new File(PINOT_PLUGINS_TAR_GZ);
    try {
      TarGzCompressionUtils.createTarGzOfDirectory(pluginsRootDir, pluginsTarGzFile.getPath());
    } catch (IOException e) {
      LOGGER.error("Failed to tar plugins directory", e);
    }
    sparkContext.addFile(pluginsTarGzFile.toString());
  }
}
