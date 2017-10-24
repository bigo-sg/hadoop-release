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

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.azuredfs.contracts.exceptions.AzureDistributedFileSystemException;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClientSession;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClientSessionFactory;

@Singleton
public class MockAdfsHttpClientSessionFactoryImpl implements AdfsHttpClientSessionFactory {
  public AdfsHttpClientSession adfsHttpClientSession;

  @Inject
  MockAdfsHttpClientSessionFactoryImpl() {
  }

  @Override
  public AdfsHttpClientSession create(FileSystem fs) throws AzureDistributedFileSystemException {
    return adfsHttpClientSession;
  }

  public AdfsHttpClientSession create(
      final String accountName,
      final String accountKey,
      final String fileSystem,
      final String hostName) {
    return new AdfsHttpClientSessionImpl(accountName, accountKey, fileSystem, hostName);
  }

  public void setSession(AdfsHttpClientSession adfsHttpClientSession) {
    this.adfsHttpClientSession = adfsHttpClientSession;
  }
}