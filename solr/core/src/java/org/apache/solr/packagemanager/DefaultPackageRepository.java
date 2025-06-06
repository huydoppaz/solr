/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.packagemanager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a serializable bean (for the JSON that is stored in /repository.json) representing a
 * repository of Solr packages. Supports standard repositories based on a webservice.
 */
public class DefaultPackageRepository extends PackageRepository {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public DefaultPackageRepository() { // this is needed for deserialization from JSON
  }

  public DefaultPackageRepository(String repositoryName, String repositoryURL) {
    this.name = repositoryName;
    this.repositoryURL = repositoryURL;
  }

  @Override
  public void refresh() {
    packages = null;
  }

  @JsonIgnore private Map<String, SolrPackage> packages;

  @Override
  public Map<String, SolrPackage> getPackages() {
    if (packages == null) {
      initPackages();
    }

    return packages;
  }

  @Override
  public SolrPackage getPackage(String packageName) {
    return getPackages().get(packageName);
  }

  @Override
  public boolean hasPackage(String packageName) {
    return getPackages().containsKey(packageName);
  }

  @Override
  public Path download(String artifactName) throws SolrException, IOException {
    Path tmpDirectory = Files.createTempDirectory("solr-packages");
    PathUtils.deleteOnExit(tmpDirectory);
    URL url = getRepoUri().resolve(artifactName).toURL();
    String fileName = FilenameUtils.getName(url.getPath());
    Path destination = tmpDirectory.resolve(fileName);

    switch (url.getProtocol()) {
      case "http":
      case "https":
      case "ftp":
        FileUtils.copyURLToFile(url, destination.toFile());
        break;
      default:
        throw new SolrException(
            ErrorCode.BAD_REQUEST, "URL protocol " + url.getProtocol() + " not supported");
    }

    return destination;
  }

  private URI getRepoUri() {
    return URI.create(repositoryURL.endsWith("/") ? repositoryURL : repositoryURL + "/");
  }

  private void initPackages() {
    try {
      final var url = getRepoUri().resolve("repository.json").toURL();
      packages =
          PackageUtils.getMapper()
              .readValue(url, new TypeReference<List<SolrPackage>>() {})
              .stream()
              .peek(pkg -> pkg.setRepository(name))
              .collect(Collectors.toMap(pkg -> pkg.name, Function.identity()));
    } catch (IOException ex) {
      throw new SolrException(ErrorCode.INVALID_STATE, ex);
    }
    if (log.isDebugEnabled()) {
      log.debug("Found {} packages in repository '{}'", this.packages.size(), name);
    }
  }
}
