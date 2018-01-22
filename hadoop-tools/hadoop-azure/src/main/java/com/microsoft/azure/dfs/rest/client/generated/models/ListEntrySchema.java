/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Code generated by Microsoft (R) AutoRest Code Generator 1.2.2.0
 * Changes may cause incorrect behavior and will be lost if the code is
 * regenerated.
 */

package com.microsoft.azure.dfs.rest.client.generated.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.hadoop.classification.InterfaceStability;

/**
 * The ListEntrySchema model.
 */
@InterfaceStability.Evolving
public class ListEntrySchema {
  /**
   * The name property.
   */
  @JsonProperty(value = "name")
  private String name;

  /**
   * The isDirectory property.
   */
  @JsonProperty(value = "isDirectory")
  private Boolean isDirectory;

  /**
   * The lastModified property.
   */
  @JsonProperty(value = "lastModified")
  private String lastModified;

  /**
   * The eTag property.
   */
  @JsonProperty(value = "eTag")
  private String eTag;

  /**
   * The contentLength property.
   */
  @JsonProperty(value = "contentLength")
  private Long contentLength;

  /**
   * The contentType property.
   */
  @JsonProperty(value = "contentType")
  private String contentType;

  /**
   * The leaseStatus property.
   */
  @JsonProperty(value = "leaseStatus")
  private String leaseStatus;

  /**
   * The leaseDuration property.
   */
  @JsonProperty(value = "leaseDuration")
  private String leaseDuration;

  /**
   * The serverEncrypted property.
   */
  @JsonProperty(value = "serverEncrypted")
  private Boolean serverEncrypted;

  /**
   * Get the name value.
   *
   * @return the name value
   */
  public String name() {
    return this.name;
  }

  /**
   * Set the name value.
   *
   * @param name the name value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withName(String name) {
    this.name = name;
    return this;
  }

  /**
   * Get the isDirectory value.
   *
   * @return the isDirectory value
   */
  public Boolean isDirectory() {
    return this.isDirectory;
  }

  /**
   * Set the isDirectory value.
   *
   * @param isDirectory the isDirectory value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withIsDirectory(Boolean isDirectory) {
    this.isDirectory = isDirectory;
    return this;
  }

  /**
   * Get the lastModified value.
   *
   * @return the lastModified value
   */
  public String lastModified() {
    return this.lastModified;
  }

  /**
   * Set the lastModified value.
   *
   * @param lastModified the lastModified value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withLastModified(String lastModified) {
    this.lastModified = lastModified;
    return this;
  }

  /**
   * Get the eTag value.
   *
   * @return the eTag value
   */
  public String eTag() {
    return this.eTag;
  }

  /**
   * Set the eTag value.
   *
   * @param eTag the eTag value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withETag(String eTag) {
    this.eTag = eTag;
    return this;
  }

  /**
   * Get the contentLength value.
   *
   * @return the contentLength value
   */
  public Long contentLength() {
    return this.contentLength;
  }

  /**
   * Set the contentLength value.
   *
   * @param contentLength the contentLength value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withContentLength(Long contentLength) {
    this.contentLength = contentLength;
    return this;
  }

  /**
   * Get the contentType value.
   *
   * @return the contentType value
   */
  public String contentType() {
    return this.contentType;
  }

  /**
   * Set the contentType value.
   *
   * @param contentType the contentType value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /**
   * Get the leaseStatus value.
   *
   * @return the leaseStatus value
   */
  public String leaseStatus() {
    return this.leaseStatus;
  }

  /**
   * Set the leaseStatus value.
   *
   * @param leaseStatus the leaseStatus value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withLeaseStatus(String leaseStatus) {
    this.leaseStatus = leaseStatus;
    return this;
  }

  /**
   * Get the leaseDuration value.
   *
   * @return the leaseDuration value
   */
  public String leaseDuration() {
    return this.leaseDuration;
  }

  /**
   * Set the leaseDuration value.
   *
   * @param leaseDuration the leaseDuration value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withLeaseDuration(String leaseDuration) {
    this.leaseDuration = leaseDuration;
    return this;
  }

  /**
   * Get the serverEncrypted value.
   *
   * @return the serverEncrypted value
   */
  public Boolean serverEncrypted() {
    return this.serverEncrypted;
  }

  /**
   * Set the serverEncrypted value.
   *
   * @param serverEncrypted the serverEncrypted value to set
   * @return the ListEntrySchema object itself.
   */
  public ListEntrySchema withServerEncrypted(Boolean serverEncrypted) {
    this.serverEncrypted = serverEncrypted;
    return this;
  }

}