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

package org.apache.hadoop.fs.azuredfs.services;

import com.google.common.base.Preconditions;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClientSession;

/**
 * File System service to provider AzureDistributedFilesystem client.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
final class AdfsHttpClientSessionImpl implements AdfsHttpClientSession {
  private final String fileSystem;
  private final String hostName;
  private final StorageCredentialsAccountAndKey storageCredentialsAccountAndKey;

  public AdfsHttpClientSessionImpl(
      final String accountName,
      final String accountKey,
      final String fileSystem,
      final String hostName) {

    Preconditions.checkNotNull(accountName, "accountName");
    Preconditions.checkNotNull(accountKey, "accountKey");
    Preconditions.checkNotNull(fileSystem, "fileSystem");
    Preconditions.checkNotNull(hostName, "hostName");

    Preconditions.checkArgument(!accountName.isEmpty(), "accountName");
    Preconditions.checkArgument(!accountKey.isEmpty(), "accountKey");
    Preconditions.checkArgument(!fileSystem.isEmpty(), "fileSystem");
    Preconditions.checkArgument(!hostName.isEmpty(), "hostName");

    this.storageCredentialsAccountAndKey = new StorageCredentialsAccountAndKey(
        accountName,
        accountKey);

    this.fileSystem = fileSystem;
    this.hostName = hostName;
  }

  @Override
  public StorageCredentialsAccountAndKey getStorageCredentialsAccountAndKey() {
    return this.storageCredentialsAccountAndKey;
  }

  public String getFileSystem() {
    return fileSystem;
  }

  public String getHostName() {
    return hostName;
  }
}