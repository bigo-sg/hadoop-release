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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.namenode.BlocksMap.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.INode.BlocksMapUpdateInfo;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory.INodesInPath;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectorySnapshottable;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotAccessControlException;
import org.apache.hadoop.hdfs.util.ByteArray;
import org.apache.hadoop.hdfs.util.ReadOnlyList;

/*************************************************
 * FSDirectory stores the filesystem directory state.
 * It handles writing/loading values to disk, and logging
 * changes as we go.
 *
 * It keeps the filename->blockset mapping always-current
 * and logged to disk.
 * 
 *************************************************/
public class FSDirectory implements FSConstants, Closeable {
  private static INodeDirectoryWithQuota createRoot(FSNamesystem namesystem) {
    final INodeDirectoryWithQuota r = new INodeDirectoryWithQuota(
        INodeDirectory.ROOT_NAME,
        namesystem.createFsOwnerPermissions(new FsPermission((short)0755)));
    final INodeDirectorySnapshottable s = new INodeDirectorySnapshottable(r);
    s.setSnapshotQuota(0);
    return s;
  }
  
  final FSNamesystem namesystem;
  final INodeDirectoryWithQuota rootDir;
  FSImage fsImage;  
  private boolean ready = false;
  private final int lsLimit;  // max list limit

  /**
   * Caches frequently used file names used in {@link INode} to reuse 
   * byte[] objects and reduce heap usage.
   */
  private final NameCache<ByteArray> nameCache;

  /** Access an existing dfs name directory. */
  FSDirectory(FSNamesystem ns, Configuration conf) {
    this(new FSImage(), ns, conf);
    fsImage.setCheckpointDirectories(FSImage.getCheckpointDirs(conf, null),
                                FSImage.getCheckpointEditsDirs(conf, null));
  }

  FSDirectory(FSImage fsImage, FSNamesystem ns, Configuration conf) {
    rootDir = createRoot(ns);
    this.fsImage = fsImage;
    fsImage.setRestoreRemovedDirs(conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_NAME_DIR_RESTORE_KEY,
        DFSConfigKeys.DFS_NAMENODE_NAME_DIR_RESTORE_DEFAULT));
    fsImage.setEditsTolerationLength(conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_EDITS_TOLERATION_LENGTH_KEY,
        DFSConfigKeys.DFS_NAMENODE_EDITS_TOLERATION_LENGTH_DEFAULT));

    namesystem = ns;
    int configuredLimit = conf.getInt(
        DFSConfigKeys.DFS_LIST_LIMIT, DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT);
    this.lsLimit = configuredLimit>0 ? 
        configuredLimit : DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT;
    
    int threshold = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_KEY,
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_DEFAULT);
    NameNode.LOG.info("Caching file names occuring more than " + threshold
        + " times ");
    nameCache = new NameCache<ByteArray>(threshold);

  }

  void loadFSImage(Collection<File> dataDirs,
                   Collection<File> editsDirs,
                   StartupOption startOpt) throws IOException {
    // format before starting up if requested
    if (startOpt == StartupOption.FORMAT) {
      fsImage.setStorageDirectories(dataDirs, editsDirs);
      fsImage.format();
      startOpt = StartupOption.REGULAR;
    }
    try {
      if (fsImage.recoverTransitionRead(dataDirs, editsDirs, startOpt)) {
        fsImage.saveNamespace(true);
      }
      FSEditLog editLog = fsImage.getEditLog();
      assert editLog != null : "editLog must be initialized";
      if (!editLog.isOpen())
        editLog.open();
      fsImage.setCheckpointDirectories(null, null);
    } catch(IOException e) {
      fsImage.close();
      throw e;
    }
    synchronized (this) {
      this.ready = true;
      this.nameCache.initialized();
      this.notifyAll();
    }
  }

  private void incrDeletedFileCount(int count) {
    if (namesystem != null)
      NameNode.getNameNodeMetrics().incrFilesDeleted(count);
  }
    
  /**
   * Shutdown the filestore
   */
  public void close() throws IOException {
    fsImage.close();
  }

  /**
   * Block until the object is ready to be used.
   */
  void waitForReady() {
    if (!ready) {
      synchronized (this) {
        while (!ready) {
          try {
            this.wait(5000);
          } catch (InterruptedException ie) {
          }
        }
      }
    }
  }

  /**
   * Add the given filename to the fs.
   */
  INodeFileUnderConstruction addFile(String path, 
                PermissionStatus permissions,
                short replication,
                long preferredBlockSize,
                String clientName,
                String clientMachine,
                DatanodeDescriptor clientNode,
                long generationStamp) 
                throws IOException {
    waitForReady();

    // Always do an implicit mkdirs for parent directory tree.
    long modTime = FSNamesystem.now();
    if (!mkdirs(new Path(path).getParent().toString(), permissions, true,
        modTime)) {
      return null;
    }
    boolean added = false;
    INodeFileUnderConstruction newNode = new INodeFileUnderConstruction(
                                 permissions,replication,
                                 preferredBlockSize, modTime, clientName, 
                                 clientMachine, clientNode);
    synchronized (rootDir) {
      added = addINode(path, newNode, -1, false);
    }
    if (!added) {
      NameNode.stateChangeLog.info("DIR* addFile: " + "failed to add " + path);
      return null;
    }
    // add create file record to log, record new generation stamp
    fsImage.getEditLog().logOpenFile(path, newNode);

    NameNode.stateChangeLog.debug("DIR* addFile: " + path + " is added");
    return newNode;
  }

  INode unprotectedAddFile( String path, 
                            PermissionStatus permissions,
                            Block[] blocks, 
                            short replication,
                            long modificationTime,
                            long atime,
                            long preferredBlockSize) {
    final INode newNode;
    long diskspace = -1; // unknown
    if (blocks == null)
      newNode = new INodeDirectory(permissions, modificationTime);
    else {
      newNode = new INodeFile(permissions, blocks.length, replication,
                              modificationTime, atime, preferredBlockSize);
      diskspace = ((INodeFile)newNode).diskspaceConsumed(blocks);
    }
    synchronized (rootDir) {
      try {
        if(addINode(path, newNode, diskspace, false) && blocks != null) {
          int nrBlocks = blocks.length;
          // Add file->block mapping
          INodeFile newF = (INodeFile) newNode;
          for (int i = 0; i < nrBlocks; i++) {
            newF.setBlock(i, namesystem.blocksMap.addINode(blocks[i], newF));
          }
        }
      } catch (IOException e) {
        return null;
      }
      return newNode;
    }
  }

  /**
   * Add a block to the file. Returns a reference to the added block.
   */
  Block addBlock(String path, INodesInPath inodesInPath, Block block)
      throws IOException {
    waitForReady();

    synchronized (rootDir) {
      final INodeFile fileNode = INodeFileUnderConstruction.valueOf(
          inodesInPath.getLastINode(), path);

      // check quota limits and updated space consumed
      updateCount(inodesInPath, 0,
          fileNode.getPreferredBlockSize() * fileNode.getFileReplication(),
          true);
      
      // associate the new list of blocks with this file
      namesystem.blocksMap.addINode(block, fileNode);
      BlockInfo blockInfo = namesystem.blocksMap.getStoredBlock(block);
      fileNode.addBlock(blockInfo);

      NameNode.stateChangeLog.debug("DIR* FSDirectory.addFile: "
                                    + path + " with " + block
                                    + " is added to the in-memory "
                                    + "file system");
    }
    return block;
  }

  /**
   * Persist the block list for the inode.
   */
  void persistBlocks(String path, INodeFileUnderConstruction file) 
                     throws IOException {
    waitForReady();

    synchronized (rootDir) {
      fsImage.getEditLog().logOpenFile(path, file);
      NameNode.stateChangeLog.debug("DIR* FSDirectory.persistBlocks: "
                                    +path+" with "+ file.getBlocks().length 
                                    +" blocks is persisted");
    }
  }

  /**
   * Close file.
   */
  void closeFile(String path, INodeFile file) throws IOException {
    waitForReady();
    synchronized (rootDir) {
      // file is closed
      fsImage.getEditLog().logCloseFile(path, file);
      if (NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.closeFile: "
                                    +path+" with "+ file.getBlocks().length 
                                    +" blocks is persisted");
      }
    }
  }

  /**
   * Remove a block to the file.
   */
  boolean removeBlock(String path, INodeFileUnderConstruction fileNode, 
                      Block block) throws IOException {
    waitForReady();

    synchronized (rootDir) {
      // modify file-> block and blocksMap
      fileNode.removeBlock(block);
      namesystem.blocksMap.removeINode(block);
      // If block is removed from blocksMap remove it from corruptReplicasMap
      namesystem.corruptReplicas.removeFromCorruptReplicasMap(block);

      // write modified block locations to log
      fsImage.getEditLog().logOpenFile(path, fileNode);
      NameNode.stateChangeLog.debug("DIR* FSDirectory.addFile: "
          + path + " with "+ block +" is added to the");
      // update space consumed
      final INodesInPath iip = rootDir.getINodesInPath4Write(path);
      updateCount(iip, 0,
          -fileNode.getPreferredBlockSize() * fileNode.getFileReplication(),
          true);
    }

    return true;
  }

  /**
   * @throws SnapshotAccessControlException 
   * @see #unprotectedRenameTo(String, String, long)
   */
  boolean renameTo(String src, String dst) throws QuotaExceededException,
      SnapshotAccessControlException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.renameTo: "
                                  +src+" to "+dst);
    }
    waitForReady();
    long now = FSNamesystem.now();
    if (!unprotectedRenameTo(src, dst, now))
      return false;
    fsImage.getEditLog().logRename(src, dst, now);
    return true;
  }

  /**
   * Change a path name
   * 
   * @param src
   *          source path
   * @param dst
   *          destination path
   * @return true if rename succeeds; false otherwise
   * @throws QuotaExceededException
   *           if the operation violates any quota limit
   * @throws SnapshotAccessControlException
   *           if the source directory is in a snapshot
   */
  boolean unprotectedRenameTo(String src, String dst, long timestamp) 
  throws QuotaExceededException, SnapshotAccessControlException {
    synchronized (rootDir) {
      INodesInPath srcInodesInPath = rootDir.getINodesInPath4Write(src);
      INode[] srcInodes = srcInodesInPath.getINodes();

      // check the validation of the source
      if (srcInodes[srcInodes.length-1] == null) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + "failed to rename " + src + " to " + dst
            + " because source does not exist");
        return false;
      } 
      if (srcInodes.length == 1) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            +"failed to rename "+src+" to "+dst+ " because source is the root");
        return false;
      }
      if (isDir(dst)) {
        dst += Path.SEPARATOR + new Path(src).getName();
      }
      
      // check the validity of the destination
      if (dst.equals(src)) {
        return true;
      }
      // dst cannot be directory or a file under src
      if (dst.startsWith(src) && 
          dst.charAt(src.length()) == Path.SEPARATOR_CHAR) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + "failed to rename " + src + " to " + dst
            + " because destination starts with src");
        return false;
      }
      
      byte[][] dstComponents = INode.getPathComponents(dst);
      INodesInPath dstInodesInPath = rootDir.getExistingPathINodes(
          dstComponents, dstComponents.length);
      if (dstInodesInPath.isSnapshot()) {
        throw new SnapshotAccessControlException(
            "Modification on RO snapshot is disallowed");
      }
      INode[] dstInodes = dstInodesInPath.getINodes();
      if (dstInodes[dstInodes.length-1] != null) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
                                     +"failed to rename "+src+" to "+dst+ 
                                     " because destination exists");
        return false;
      }
      if (dstInodes[dstInodes.length-2] == null) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            +"failed to rename "+src+" to "+dst+ 
            " because destination's parent does not exist");
        return false;
      }
      
      // Ensure dst has quota to accommodate rename
      verifyQuotaForRename(srcInodes,dstInodes);
      
      boolean added = false;
      INode srcChild = null;
      byte[] srcChildName = null;
      try {
        // remove src
        srcChild = removeLastINode(srcInodesInPath);
        if (srcChild == null) {
          NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
              + "failed to rename " + src + " to " + dst
              + " because the source can not be removed");
          return false;
        }
        srcChildName = srcChild.getLocalNameBytes();
        srcChild.setLocalName(dstComponents[dstInodes.length-1]);
        
        // add src to the destination
        added = addLastINodeNoQuotaCheck(dstInodesInPath, srcChild, -1, false);
        if (added) {
          srcChild = null;
          if (NameNode.stateChangeLog.isDebugEnabled()) {
            NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedRenameTo: " + src
                    + " is renamed to " + dst);
          }
          // update modification time of dst and the parent of src
          srcInodes[srcInodes.length - 2].updateModificationTime(timestamp,
              srcInodesInPath.getLatestSnapshot());
          dstInodes[dstInodes.length - 2].updateModificationTime(timestamp,
              dstInodesInPath.getLatestSnapshot());
          return true;
        }
      } finally {
        if (!added && srcChild != null) {
          // put it back
          srcChild.setLocalName(srcChildName);
          addLastINodeNoQuotaCheck(srcInodesInPath, srcChild, -1, false);
        }
      }
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          +"failed to rename "+src+" to "+dst);
      return false;
    }
  }

  /**
   * Set file replication
   * 
   * @param src file name
   * @param replication new replication
   * @param oldReplication old replication - output parameter
   * @return array of file blocks
   * @throws IOException
   */
  Block[] setReplication(String src, 
                         short replication,
                         int[] oldReplication
                         ) throws IOException {
    waitForReady();
    Block[] fileBlocks = unprotectedSetReplication(src, replication, oldReplication);
    if (fileBlocks != null)  // log replication change
      fsImage.getEditLog().logSetReplication(src, replication);
    return fileBlocks;
  }

  Block[] unprotectedSetReplication(String src, short replication,
      int[] oldReplication) throws IOException {
    if (oldReplication == null)
      oldReplication = new int[1];
    oldReplication[0] = -1;
    Block[] fileBlocks = null;
    synchronized(rootDir) {
      INodesInPath inodesInPath = rootDir.getINodesInPath4Write(src); 
      INode[] inodes = inodesInPath.getINodes();
      INode inode = inodes[inodes.length - 1];
      if (inode == null)
        return null;
      if (inode.isDirectory())
        return null;
      INodeFile fileNode = (INodeFile)inode;
      oldReplication[0] = fileNode.getFileReplication();

      // check disk quota
      long dsDelta = (replication - oldReplication[0]) *
           (fileNode.diskspaceConsumed()/oldReplication[0]);
      updateCount(inodesInPath, 0, dsDelta, true);

      fileNode.setFileReplication(replication, inodesInPath.getLatestSnapshot());
      fileBlocks = fileNode.getBlocks();
    }
    return fileBlocks;
  }

  /**
   * Get the blocksize of a file
   * @param path the file path
   * @return the block size of the file. 
   * @throws IOException if it is a directory or does not exist.
   */
  long getPreferredBlockSize(String path) throws IOException {
    synchronized (rootDir) {
      return INodeFile.valueOf(rootDir.getNode(path), path)
          .getPreferredBlockSize();
    }
  }

  boolean exists(String src) {
    src = normalizePath(src);
    synchronized(rootDir) {
      INode inode = rootDir.getNode(src);
      if (inode == null) {
         return false;
      }
      return !inode.isFile() || ((INodeFile)inode).getBlocks() != null;
    }
  }
  
  void setPermission(String src, FsPermission permission
      ) throws IOException {
    unprotectedSetPermission(src, permission);
    fsImage.getEditLog().logSetPermissions(src, permission);
  }

  void unprotectedSetPermission(String src, FsPermission permissions)
      throws FileNotFoundException, SnapshotAccessControlException {
    synchronized (rootDir) {
      final INodesInPath inodesInPath = rootDir.getINodesInPath4Write(src);
      final INode inode = inodesInPath.getLastINode();
      if (inode == null) {
        throw new FileNotFoundException("File does not exist: " + src);
      }
      inode.setPermission(permissions, inodesInPath.getLatestSnapshot());
    }
  }

  void setOwner(String src, String username, String groupname
      ) throws IOException {
    unprotectedSetOwner(src, username, groupname);
    fsImage.getEditLog().logSetOwner(src, username, groupname);
  }

  void unprotectedSetOwner(String src, String username, String groupname)
      throws FileNotFoundException, SnapshotAccessControlException {
    synchronized(rootDir) {
      final INodesInPath inodesInPath = rootDir.getINodesInPath4Write(src);
      INode inode = inodesInPath.getLastINode();
      if(inode == null) {
          throw new FileNotFoundException("File does not exist: " + src);
      }
      if (username != null) {
        inode = inode.setUser(username, inodesInPath.getLatestSnapshot());
      }
      if (groupname != null) {
        inode.setGroup(groupname, inodesInPath.getLatestSnapshot());
      }
    }
  }
    
  /**
   * Delete the target directory and collect the blocks under it
   * 
   * @param src
   *          Path of a directory to delete
   * @param collectedBlocks
   *          Blocks under the deleted directory
   * @return true on successful deletion; else false
   */
  boolean delete(String src, BlocksMapUpdateInfo collectedBlocks)
      throws IOException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.delete: " + src);
    }
    waitForReady();
    long now = FSNamesystem.now();
    final int filesRemoved;
    synchronized (rootDir) {
      final INodesInPath inodesInPath = rootDir
          .getINodesInPath4Write(normalizePath(src));
      if (!deleteAllowed(inodesInPath, src) ) {
        filesRemoved = 0;
      } else {
        // Before removing the node, first check if the targetNode is for a
        // snapshottable dir with snapshots, or its descendants have
        // snapshottable dir with snapshots
        final INode targetNode = inodesInPath.getLastINode();
        List<INodeDirectorySnapshottable> snapshottableDirs = 
            new ArrayList<INodeDirectorySnapshottable>();
        INode snapshotNode = hasSnapshot(targetNode, snapshottableDirs);
        if (snapshotNode != null) {
          throw new IOException("The direcotry " + targetNode.getFullPathName()
              + " cannot be deleted since " + snapshotNode.getFullPathName()
              + " is snapshottable and already has snapshots");
        }
        filesRemoved = unprotectedDelete(inodesInPath, collectedBlocks, now);
        if (snapshottableDirs.size() > 0) {
          // There are some snapshottable directories without snapshots to be
          // deleted. Need to update the SnapshotManager.
          namesystem.removeSnapshottableDirs(snapshottableDirs);
        }
      }
    }
    if (filesRemoved <= 0) {
      return false;
    }
    incrDeletedFileCount(filesRemoved);
    // Blocks will be deleted later by the caller of this method
    FSNamesystem.getFSNamesystem().removePathAndBlocks(src, null);
    fsImage.getEditLog().logDelete(src, now);
    return true;
  }
  
  /** Return if a directory is empty or not **/
  boolean isDirEmpty(String src) {
	   boolean dirNotEmpty = true;
    if (!isDir(src)) {
      return true;
    }
    synchronized(rootDir) {
      final INodesInPath inodesInPath = rootDir.getLastINodeInPath(src);
      final INode targetNode = inodesInPath.getINode(0);
      assert targetNode != null : "should be taken care in isDir() above";
      final Snapshot s = inodesInPath.getPathSnapshot();
      if (((INodeDirectory) targetNode).getChildrenList(s).size() != 0) {
        dirNotEmpty = false;
      }
    }
    return dirNotEmpty;
  }
  
  private static boolean deleteAllowed(final INodesInPath iip,
      final String src) {
    final INode[] inodes = iip.getINodes(); 
    if (inodes == null || inodes.length == 0
        || inodes[inodes.length - 1] == null) {
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
            + "failed to remove " + src + " because it does not exist");
      }
      return false;
    } else if (inodes.length == 1) { // src is the root
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedDelete: "
          + "failed to remove " + src
          + " because the root is not allowed to be deleted");
      return false;
    }
    return true;
  }
  
  /**
   * Delete a path from the name space Update the count at each ancestor
   * directory with quota
   * 
   * @param src
   *          a string representation of a path to an inode
   * 
   * @param mTime
   *          the time the inode is removed
   * @throws SnapshotAccessControlException
   * @throws FileNotFoundException
   */
  void unprotectedDelete(String src, long mtime)
      throws SnapshotAccessControlException, FileNotFoundException {
    BlocksMapUpdateInfo collectedBlocks = new BlocksMapUpdateInfo();
    
    final INodesInPath inodesInPath = rootDir
        .getINodesInPath4Write(normalizePath(src));
    final int filesRemoved = deleteAllowed(inodesInPath, src)?
        unprotectedDelete(inodesInPath, collectedBlocks, mtime): 0;
    if (filesRemoved > 0) {
      namesystem.removePathAndBlocks(src, collectedBlocks);
    }
  }
  
  /**
   * Delete a path from the name space.
   * Update the count at each ancestor directory with quota.
   * 
   * @param inodesInPath the INodes resolved from the path
   * @param collectedBlocks blocks collected from the deleted path
   * @param mtime the time the inode is removed
   * @return the number of inodes deleted; 0 if no inodes are deleted.
   */
  int unprotectedDelete(INodesInPath inodesInPath,
      BlocksMapUpdateInfo collectedBlocks, long mtime) {
    final INode targetNode = removeLastINode(inodesInPath);
    if (targetNode == null) {
      return 0;
    }
    // set the parent's modification time
    final INode[] inodes = inodesInPath.getINodes();
    final Snapshot latestSnapshot = inodesInPath.getLatestSnapshot();
    final INodeDirectory parent = (INodeDirectory)inodes[inodes.length - 2];
    parent.updateModificationTime(mtime, latestSnapshot);
    
    final INode snapshotCopy = parent.getChild(targetNode.getLocalNameBytes(),
        latestSnapshot);
    // if snapshotCopy == targetNode, it means that the file is also stored in
    // a snapshot so that the block should not be removed.
    final int filesRemoved = snapshotCopy == targetNode ? 0 : targetNode
        .destroySubtreeAndCollectBlocks(null, collectedBlocks);
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
          + targetNode.getFullPathName() + " is removed");
    }
    return filesRemoved;
  }
    
  /**
   * Check if the given INode (or one of its descendants) is snapshottable and
   * already has snapshots.
   * 
   * @param target
   *          The given INode
   * @param snapshottableDirs
   *          The list of directories that are snapshottable but do not have
   *          snapshots yet
   * @return The INode which is snapshottable and already has snapshots.
   */
  private static INode hasSnapshot(INode target,
      List<INodeDirectorySnapshottable> snapshottableDirs) {
    if (target instanceof INodeDirectory) {
      INodeDirectory targetDir = (INodeDirectory) target;
      if (targetDir.isSnapshottable()) {
        INodeDirectorySnapshottable ssTargetDir = 
            (INodeDirectorySnapshottable) targetDir;
        if (ssTargetDir.getNumSnapshots() > 0) {
          return target;
        } else {
          snapshottableDirs.add(ssTargetDir);
        }
      } 
      for (INode child : targetDir.getChildrenList(null)) {
        INode snapshotDir = hasSnapshot(child, snapshottableDirs);
        if (snapshotDir != null) {
          return snapshotDir;
        }
      }
    }
    return null;
  }
  
  /**
   * Replaces the specified INodeFile with the specified one.
   */
  public void replaceINodeFile(String path, INodeFile oldnode,
      INodeFile newnode, Snapshot latest) throws IOException {
    synchronized (rootDir) {
      unprotectedReplaceINodeFile(path, oldnode, newnode, latest);
    }
  }

  void unprotectedReplaceINodeFile(final String path, final INodeFile oldnode,
      final INodeFile newnode, Snapshot latest) {
    final INodeDirectory parent = oldnode.getParent();
    final INode removed = parent.removeChild(oldnode, latest);
    if (removed != oldnode) {
      throw new IllegalStateException("removed != oldnode=" + oldnode
          + ", removed=" + removed);
    }
    
    oldnode.setParent(null);
    parent.addChild(newnode, false, latest);

    /* Currently oldnode and newnode are assumed to contain the same
     * blocks. Otherwise, blocks need to be removed from the blocksMap.
     */
    int index = 0;
    for (BlockInfo b : newnode.getBlocks()) {
      BlockInfo info = namesystem.blocksMap.addINode(b, newnode);
      newnode.setBlock(index, info); // inode refers to the block in BlocksMap
      index++;
    }
  }

  /**
   * Get a partial listing of the indicated directory
   * 
   * @param src the directory name
   * @param startAfter the name to start listing after
   * @return a partial listing starting after startAfter 
   */
  DirectoryListing getListing(String src, byte[] startAfter) {
    String srcs = normalizePath(src);

    synchronized (rootDir) {
      final INodesInPath inodesInPath = rootDir.getLastINodeInPath(srcs);
      final Snapshot snapshot = inodesInPath.getPathSnapshot();
      INode targetNode = inodesInPath.getINode(0);
      if (targetNode == null)
        return null;
      
      if (!targetNode.isDirectory()) {
        return new DirectoryListing(new HdfsFileStatus[]{createFileStatus(
            HdfsFileStatus.EMPTY_NAME, targetNode, snapshot)}, 0);
      }
      
      INodeDirectory dirInode = (INodeDirectory)targetNode; 
      final ReadOnlyList<INode> contents = dirInode
          .getChildrenList(inodesInPath.getPathSnapshot());
      int startChild = INodeDirectory.nextChild(contents, startAfter);
      int totalNumChildren = contents.size();
      int numOfListing = Math.min(totalNumChildren-startChild, this.lsLimit);
      HdfsFileStatus listing[] = new HdfsFileStatus[numOfListing];
      for (int i=0; i<numOfListing; i++) {
        INode cur = contents.get(startChild+i);
        listing[i] = createFileStatus(cur.getLocalNameBytes(), cur, snapshot);
      }
      return new DirectoryListing(
          listing, totalNumChildren-startChild-numOfListing);
    }
  }

  /** Get the file info for a specific file.
   * @param src The string representation of the path to the file
   * @return object containing information regarding the file
   *         or null if file not found
   */
  HdfsFileStatus getFileInfo(String src) {
    String srcs = normalizePath(src);
    synchronized (rootDir) {
      final INodesInPath inodesInPath = rootDir.getLastINodeInPath(srcs);
      final INode i = inodesInPath.getINode(0);
      return i == null ? null : createFileStatus(HdfsFileStatus.EMPTY_NAME, i,
          inodesInPath.getPathSnapshot());
    }
  }

  /**
   * Get the blocks associated with the file.
   */
  Block[] getFileBlocks(String src) {
    waitForReady();
    synchronized (rootDir) {
      final INode i = rootDir.getNode(src);
      return i != null && i.isFile()? ((INodeFile)i).getBlocks(): null;
    }
  }
  
  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INode getINode(String src) {
    return getLastINodeInPath(src).getINode(0);
  }
  
  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INodesInPath getLastINodeInPath(String src) {
    synchronized (rootDir) {
      return rootDir.getLastINodeInPath(src);
    }
  }
  
  /**
   * Get {@link INode} associated with the file / directory.
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  public INode getINode4Write(String src)
      throws SnapshotAccessControlException {
    synchronized (rootDir) {
      return rootDir.getINode4Write(src);
    }
  }
  
  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INodesInPath getINodesInPath4Write(String src)
      throws SnapshotAccessControlException {
    synchronized (rootDir) {
      return rootDir.getINodesInPath4Write(src);
    }
  }
  
  /** 
   * Check whether the filepath could be created
   * @throws SnapshotAccessControlException 
   */
  boolean isValidToCreate(String src) throws SnapshotAccessControlException {
    String srcs = normalizePath(src);
    synchronized (rootDir) {
      if (srcs.startsWith("/") && !srcs.endsWith("/")
          && rootDir.getINode4Write(srcs) == null) {
        return true;
      } else {
        return false;
      }
    }
  }
  
  /**
   * Check whether the path specifies a directory
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  boolean isDirMutable(String src) throws SnapshotAccessControlException {
    src = normalizePath(src);
    synchronized (rootDir) {
      INode node = rootDir.getINode4Write(src);
      return node != null && node.isDirectory();
    }
  }

  /**
   * Check whether the path specifies a directory
   */
  boolean isDir(String src) {
    synchronized (rootDir) {
      INode node = rootDir.getNode(normalizePath(src));
      return node != null && node.isDirectory();
    }
  }

  /** Updates namespace and diskspace consumed for all
   * directories until the parent directory of file represented by path.
   * 
   * @param path path for the file.
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @throws QuotaExceededException if the new count violates any quota limit
   * @throws SnapshotAccessControlException if path is in read-only snapshot
   * @throws FileNotFoundException if path does not exist.
   */
  void updateSpaceConsumed(String path, long nsDelta, long dsDelta)
      throws QuotaExceededException, FileNotFoundException,
      SnapshotAccessControlException {
    synchronized (rootDir) {
      INodesInPath iip = rootDir.getINodesInPath4Write(path);
      if (iip.getLastINode() == null) {
        throw new FileNotFoundException(path + " does not exist under rootDir.");
      }
      updateCount(iip, nsDelta, dsDelta, true);
    }
  }
  
  private void updateCount(INodesInPath iip, long nsDelta, long dsDelta,
      boolean checkQuota) throws QuotaExceededException {
    updateCount(iip, iip.getINodes().length - 1, nsDelta, dsDelta, checkQuota);
  }
  
  /** update count of each inode with quota
   * 
   * @param inodesInPath inodes on a path
   * @param numOfINodes the number of inodes to update starting from index 0
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @param checkQuota if true then check if quota is exceeded
   * @throws QuotaExceededException if the new count violates any quota limit
   */
  private void updateCount(INodesInPath inodesInPath, int numOfINodes, 
                           long nsDelta, long dsDelta, boolean checkQuota)
                           throws QuotaExceededException {
    if (!ready) {
      //still intializing. do not check or update quotas.
      return;
    }
    final INode[] inodes = inodesInPath.getINodes();
    if (numOfINodes > inodes.length) {
      numOfINodes = inodes.length;
    }
    if (checkQuota) {
      verifyQuota(inodes, numOfINodes, nsDelta, dsDelta, null);
    }
    for(int i = 0; i < numOfINodes; i++) {
      if (inodes[i].isQuotaSet()) { // a directory with quota
        INodeDirectoryWithQuota node =(INodeDirectoryWithQuota)inodes[i]; 
        node.addSpaceConsumed(nsDelta, dsDelta);
      }
    }
  }
  
  /** 
   * update quota of each inode and check to see if quota is exceeded. 
   * See {@link #updateCount(INode[], int, long, long, boolean)}
   */ 
  private void updateCountNoQuotaCheck(INodesInPath inodes, int numOfINodes,
      long nsDelta, long dsDelta) {
    try {
      updateCount(inodes, numOfINodes, nsDelta, dsDelta, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.warn("FSDirectory.updateCountNoQuotaCheck - unexpected ", e);
    }
  }
  
  /** Return the name of the path represented by inodes at [0, pos] */
  private static String getFullPathName(INode[] inodes, int pos) {
    StringBuilder fullPathName = new StringBuilder();
    for (int i=1; i<=pos; i++) {
      fullPathName.append(Path.SEPARATOR_CHAR).append(inodes[i].getLocalName());
    }
    return fullPathName.toString();
  }
  
  /** Return the full path name of the specified inode */
  static String getFullPathName(INode inode) {
    // calculate the depth of this inode from root
    int depth = 0;
    for (INode i = inode; i != null; i = i.parent) {
      depth++;
    }
    INode[] inodes = new INode[depth];

    // fill up the inodes in the path from this inode to root
    for (int i = 0; i < depth; i++) {
      inodes[depth-i-1] = inode;
      inode = inode.parent;
    }
    return getFullPathName(inodes, depth-1);
  }
  
  /**
   * Create a directory 
   * If ancestor directories do not exist, automatically create them.

   * @param src string representation of the path to the directory
   * @param permissions the permission of the directory
   * @param inheritPermission if the permission of the directory should inherit
   *                          from its parent or not. The automatically created
   *                          ones always inherit its permission from its parent
   * @param now creation time
   * @return true if the operation succeeds false otherwise
   * @throws FileNotFoundException if an ancestor or itself is a file
   * @throws QuotaExceededException if directory creation violates 
   *                                any quota limit
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  boolean mkdirs(String src, PermissionStatus permissions,
      boolean inheritPermission, long now) throws FileNotFoundException,
      QuotaExceededException, SnapshotAccessControlException {
    src = normalizePath(src);
    String[] names = INode.getPathNames(src);
    byte[][] components = INode.getPathComponents(names);

    synchronized(rootDir) {
      INodesInPath inodesInPath = rootDir.getExistingPathINodes(components,
          components.length);
      if (inodesInPath.isSnapshot()) {
        throw new SnapshotAccessControlException(
            "Modification on RO snapshot is disallowed");
      }
      INode[] inodes = inodesInPath.getINodes();
      // find the index of the first null in inodes[]
      StringBuilder pathbuilder = new StringBuilder();
      int i = 1;
      for(; i < inodes.length && inodes[i] != null; i++) {
        pathbuilder.append(Path.SEPARATOR + names[i]);
        if (!inodes[i].isDirectory()) {
          throw new FileNotFoundException("Parent path is not a directory: "
              + pathbuilder);
        }
      }

      // create directories beginning from the first null index
      for(; i < inodes.length; i++) {
        pathbuilder.append(Path.SEPARATOR + names[i]);
        String cur = pathbuilder.toString();
        unprotectedMkdir(inodesInPath, i, components[i], permissions,
            inheritPermission || i != components.length-1, now);
        if (inodes[i] == null) {
          return false;
        }
        // Directory creation also count towards FilesCreated
        // to match count of FilesDeleted metric. 
        if (namesystem != null)
          NameNode.getNameNodeMetrics().incrNumFilesCreated();
        fsImage.getEditLog().logMkDir(cur, inodes[i]);
        NameNode.stateChangeLog.debug(
            "DIR* FSDirectory.mkdirs: created directory " + cur);
      }
    }
    return true;
  }

  INode unprotectedMkdir(String src, PermissionStatus permissions,
                          long timestamp) throws QuotaExceededException {
    byte[][] components = INode.getPathComponents(src);
    synchronized (rootDir) {
      INodesInPath inodesInPath = rootDir.getExistingPathINodes(components,
          components.length);
      INode[] inodes = inodesInPath.getINodes();
      unprotectedMkdir(inodesInPath, inodes.length - 1,
          components[inodes.length - 1], permissions, false, timestamp);
      return inodes[inodes.length-1];
    }
  }

  /** create a directory at index pos.
   * The parent path to the directory is at [0, pos-1].
   * All ancestors exist. Newly created one stored at index pos.
   */
  private void unprotectedMkdir(INodesInPath inodesInPath, int pos,
      byte[] name, PermissionStatus permission, boolean inheritPermission,
      long timestamp) throws QuotaExceededException {
    final INodeDirectory dir = new INodeDirectory(name, permission, timestamp);
    if (addChild(inodesInPath, pos, dir, -1, inheritPermission, true)) {
      inodesInPath.setINode(pos, dir);
    }
  }
  
  /** Add a node child to the namespace. The full path name of the node is src.
   * childDiskspace should be -1, if unknown. 
   * @throw QuotaExceededException is thrown if it violates quota limit
   */
  private boolean addINode(String src, INode child, long childDiskspace,
      boolean inheritPermission) throws QuotaExceededException {
    byte[][] components = INode.getPathComponents(src);
    child.setLocalName(components[components.length-1]);
    cacheName(child);
    synchronized (rootDir) {
      INodesInPath inodesInPath = rootDir.getExistingPathINodes(components,
          components.length);
      return addLastINode(inodesInPath, child, childDiskspace,
          inheritPermission, true);
    }
  }
  
  /**
   * The same as {@link #addChild(INodesInPath, int, INode, boolean, boolean)}
   * with pos = length - 1.
   */
  private boolean addLastINode(INodesInPath inodesInPath, INode inode,
      long childDiskspace, boolean inheritPermission, boolean checkQuota)
      throws QuotaExceededException {
    final int pos = inodesInPath.getINodes().length - 1;
    return addChild(inodesInPath, pos, inode, childDiskspace,
        inheritPermission, checkQuota);
  }

  /**
   * Verify quota for adding or moving a new INode with required 
   * namespace and diskspace to a given position.
   *  
   * @param inodes INodes corresponding to a path
   * @param pos position where a new INode will be added
   * @param nsDelta needed namespace
   * @param dsDelta needed diskspace
   * @param commonAncestor Last node in inodes array that is a common ancestor
   *          for a INode that is being moved from one location to the other.
   *          Pass null if a node is not being moved.
   * @throws QuotaExceededException if quota limit is exceeded.
   */
  private void verifyQuota(INode[] inodes, int pos, long nsDelta, long dsDelta,
      INode commonAncestor) throws QuotaExceededException {
    if (!ready) {
      // Do not check quota if edits log is still being processed
      return;
    }
    if (pos>inodes.length) {
      pos = inodes.length;
    }
    int i = pos - 1;
    try {
      // check existing components in the path  
      for(; i >= 0; i--) {
        if (commonAncestor == inodes[i]) {
          // Moving an existing node. Stop checking for quota when common
          // ancestor is reached
          return;
        }
        if (inodes[i].isQuotaSet()) { // a directory with quota
          INodeDirectoryWithQuota node =(INodeDirectoryWithQuota)inodes[i]; 
          node.verifyQuota(nsDelta, dsDelta);
        }
      }
    } catch (QuotaExceededException e) {
      e.setPathName(getFullPathName(inodes, i));
      throw e;
    }
  }
  
  /**
   * Verify quota for rename operation where srcInodes[srcInodes.length-1] moves
   * dstInodes[dstInodes.length-1]
   * 
   * @param srcInodes directory from where node is being moved.
   * @param dstInodes directory to where node is moved to.
   * @throws QuotaExceededException if quota limit is exceeded.
   */
  private void verifyQuotaForRename(INode[] srcInodes, INode[]dstInodes)
      throws QuotaExceededException {
    if (!ready) {
      // Do not check quota if edits log is still being processed
      return;
    }
    INode srcInode = srcInodes[srcInodes.length - 1];
    INode commonAncestor = null;
    for(int i =0;srcInodes[i] == dstInodes[i]; i++) {
      commonAncestor = srcInodes[i];
    }
    INode.DirCounts counts = new INode.DirCounts();
    srcInode.spaceConsumedInTree(counts);
    verifyQuota(dstInodes, dstInodes.length - 1, counts.getNsCount(),
            counts.getDsCount(), commonAncestor);
  }
  
  /** Add a node child to the inodes at index pos. 
   * Its ancestors are stored at [0, pos-1].
   * @return the added node. 
   * @throw QuotaExceededException is thrown if it violates quota limit
   */
  private boolean addChild(INodesInPath inodesInPath, int pos,
      INode child, long childDiskspace, boolean inheritPermission,
      boolean checkQuota) throws QuotaExceededException {
    final INode[] inodes = inodesInPath.getINodes();
    INode.DirCounts counts = new INode.DirCounts();
    child.spaceConsumedInTree(counts);
    if (childDiskspace < 0) {
      childDiskspace = counts.getDsCount();
    }
    updateCount(inodesInPath, pos, counts.getNsCount(), childDiskspace,
        checkQuota);
    final boolean added = ((INodeDirectory) inodes[pos - 1]).addChild(child,
        inheritPermission, inodesInPath.getLatestSnapshot());
    if (!added) {
      updateCount(inodesInPath, pos, -counts.getNsCount(), -childDiskspace,
          true);
    }
    return added;
  }
  
  private boolean addLastINodeNoQuotaCheck(INodesInPath inodesInPath,
      INode child, long childDiskspace, boolean inheritPermission) {
    try {
      return addLastINode(inodesInPath, child, childDiskspace,
          inheritPermission, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.warn("FSDirectory.addChildNoQuotaCheck - unexpected", e); 
    }
    return false;
  }
  
  /** 
   * Remove the last inode in the path from the namespace.
   * Its ancestors are stored at [0, pos-1].
   * Count of each ancestor with quota is also updated.
   * @return the removed node; null if the removal fails.
   */
  private INode removeLastINode(INodesInPath inodesInPath) {
    final INode[] inodes = inodesInPath.getINodes();
    final int pos = inodes.length - 1;
    INode removedNode = ((INodeDirectory)inodes[pos-1]).removeChild(
        inodes[pos], inodesInPath.getLatestSnapshot());
    if (removedNode != null) {
      inodesInPath.setINode(pos - 1, removedNode.getParent());
      INode.DirCounts counts = new INode.DirCounts();
      removedNode.spaceConsumedInTree(counts);
      updateCountNoQuotaCheck(inodesInPath, pos,
                  -counts.getNsCount(), -counts.getDsCount());
    }
    return removedNode;
  }
  
  /**
   */
  String normalizePath(String src) {
    if (src.length() > 1 && src.endsWith("/")) {
      src = src.substring(0, src.length() - 1);
    }
    return src;
  }

  ContentSummary getContentSummary(String src) throws IOException {
    String srcs = normalizePath(src);
    synchronized (rootDir) {
      INode targetNode = rootDir.getNode(srcs);
      if (targetNode == null) {
        throw new FileNotFoundException("File does not exist: " + srcs);
      }
      else {
        return targetNode.computeContentSummary();
      }
    }
  }

  /** Update the count of each directory with quota in the namespace
   * A directory's count is defined as the total number inodes in the tree
   * rooted at the directory.
   * 
   * This is an update of existing state of the filesystem and does not
   * throw QuotaExceededException.
   */
  void updateCountForINodeWithQuota() {
    updateCountForINodeWithQuota(rootDir, new INode.DirCounts(), 
                                 new ArrayList<INode>(50));
  }
  
  /** 
   * Update the count of the directory if it has a quota and return the count
   * 
   * This does not throw a QuotaExceededException. This is just an update
   * of of existing state and throwing QuotaExceededException does not help
   * with fixing the state, if there is a problem.
   * 
   * @param dir the root of the tree that represents the directory
   * @param counters counters for name space and disk space
   * @param nodesInPath INodes for the each of components in the path.
   */
  private static void updateCountForINodeWithQuota(INodeDirectory dir, 
                                               INode.DirCounts counts,
                                               ArrayList<INode> nodesInPath) {
    long parentNamespace = counts.nsCount;
    long parentDiskspace = counts.dsCount;
    
    counts.nsCount = 1L;//for self. should not call node.spaceConsumedInTree()
    counts.dsCount = 0L;
    
    /* We don't need nodesInPath if we could use 'parent' field in 
     * INode. using 'parent' is not currently recommended. */
    nodesInPath.add(dir);

    for (INode child : dir.getChildrenList(null)) {
      if (child.isDirectory()) {
        updateCountForINodeWithQuota((INodeDirectory)child, 
                                     counts, nodesInPath);
      } else { // reduce recursive calls
        counts.nsCount += 1;
        counts.dsCount += ((INodeFile)child).diskspaceConsumed();
      }
    }
      
    if (dir.isQuotaSet()) {
      ((INodeDirectoryWithQuota)dir).setSpaceConsumed(counts.nsCount,
                                                      counts.dsCount);

      // check if quota is violated for some reason.
      if ((dir.getNsQuota() >= 0 && counts.nsCount > dir.getNsQuota()) ||
          (dir.getDsQuota() >= 0 && counts.dsCount > dir.getDsQuota())) {

        // can only happen because of a software bug. the bug should be fixed.
        StringBuilder path = new StringBuilder(512);
        for (INode n : nodesInPath) {
          path.append('/');
          path.append(n.getLocalName());
        }
        
        NameNode.LOG.warn("Quota violation in image for " + path + 
                          " (Namespace quota : " + dir.getNsQuota() +
                          " consumed : " + counts.nsCount + ")" +
                          " (Diskspace quota : " + dir.getDsQuota() +
                          " consumed : " + counts.dsCount + ").");
      }            
    }
      
    // pop 
    nodesInPath.remove(nodesInPath.size()-1);
    
    counts.nsCount += parentNamespace;
    counts.dsCount += parentDiskspace;
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the contract.
   * Sets quota for for a directory.
   * @returns INodeDirectory if any of the quotas have changed. null other wise.
   * @throws FileNotFoundException if the path does not exist or is a file
   * @throws QuotaExceededException if the directory tree size is 
   *                                greater than the given quota
   * @throws SnapshotAccessControlException if the path is in RO snapshot
   * @throws PathIsNotDirectoryException if the path is not a directory
   */
  INodeDirectory unprotectedSetQuota(String src, long nsQuota, long dsQuota)
      throws FileNotFoundException, QuotaExceededException,
      SnapshotAccessControlException {
    // sanity check
    if ((nsQuota < 0 && nsQuota != FSConstants.QUOTA_DONT_SET && 
         nsQuota < FSConstants.QUOTA_RESET) || 
        (dsQuota < 0 && dsQuota != FSConstants.QUOTA_DONT_SET && 
          dsQuota < FSConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Illegal value for nsQuota or " +
                                         "dsQuota : " + nsQuota + " and " +
                                         dsQuota);
    }
    
    String srcs = normalizePath(src);
    final INodesInPath iip = rootDir.getINodesInPath4Write(srcs);
    INodeDirectory dirNode = INodeDirectory.valueOf(iip.getLastINode(), srcs);
    if (dirNode.isRoot() && nsQuota == FSConstants.QUOTA_RESET) {
      throw new IllegalArgumentException("Cannot clear namespace quota on root.");
    } else { // a directory inode
      long oldNsQuota = dirNode.getNsQuota();
      long oldDsQuota = dirNode.getDsQuota();
      if (nsQuota == FSConstants.QUOTA_DONT_SET) {
        nsQuota = oldNsQuota;
      }
      if (dsQuota == FSConstants.QUOTA_DONT_SET) {
        dsQuota = oldDsQuota;
      }        
      
      final Snapshot latest = iip.getLatestSnapshot();
      if (dirNode instanceof INodeDirectoryWithQuota) {
        // a directory with quota; so set the quota to the new value
        ((INodeDirectoryWithQuota) dirNode).setQuota(nsQuota, dsQuota, latest);
        if (!dirNode.isQuotaSet() && latest == null) {
          // will not come here for root because root's nsQuota is always set
          return dirNode.replaceSelf4INodeDirectory();
        }
      } else {
        // a non-quota directory; so replace it with a directory with quota
        return dirNode.replaceSelf4Quota(latest, nsQuota, dsQuota);
      }      
      return (oldNsQuota != nsQuota || oldDsQuota != dsQuota) ? dirNode : null;
    }
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the 
   * contract.
   * @throws SnapshotAccessControlException if path is in RO snapshot
   * @see #unprotectedSetQuota(String, long, long)
   */
  void setQuota(String src, long nsQuota, long dsQuota)
      throws FileNotFoundException, QuotaExceededException,
      SnapshotAccessControlException {
    synchronized (rootDir) {    
      INodeDirectory dir = unprotectedSetQuota(src, nsQuota, dsQuota);
      if (dir != null) {
        fsImage.getEditLog().logSetQuota(src, dir.getNsQuota(), 
                                         dir.getDsQuota());
      }
    }
  }
  
  long totalInodes() {
    synchronized (rootDir) {
      return rootDir.numItemsInTree();
    }
  }

  /**
   * Sets the access time on the file. Logs it in the transaction log
   */
  void setTimes(String src, INodeFile inode, long mtime, long atime,
      boolean force, Snapshot latest) {
    boolean status = false;
    synchronized (rootDir) {
      status = unprotectedSetTimes(src, inode, mtime, atime, force, latest);
    }
    if (status) {
      fsImage.getEditLog().logTimes(src, mtime, atime);
    }
  }

  boolean unprotectedSetTimes(String src, long mtime, long atime, boolean force) {
    final INodesInPath i = getLastINodeInPath(src);
    return unprotectedSetTimes(src, i.getLastINode(), mtime, atime, force,
        i.getLatestSnapshot());
  }

  private boolean unprotectedSetTimes(String src, INode inode, long mtime,
      long atime, boolean force, Snapshot latest) {
    boolean status = false;
    if (mtime != -1) {
      inode = inode.setModificationTime(mtime, latest);
      status = true;
    }
    if (atime != -1) {
      long inodeTime = inode.getAccessTime(null);

      // if the last access time update was within the last precision interval, then
      // no need to store access time
      if (atime <= inodeTime + namesystem.getAccessTimePrecision() && !force) {
        status =  false;
      } else {
        inode.setAccessTime(atime, latest);
        status = true;
      }
    } 
    return status;
  }
  
  /**
   * Create FileStatus by file INode 
   */
  private static HdfsFileStatus createFileStatus(byte[] path, INode node,
      Snapshot snapshot) {
    // length is zero for directories
    return new HdfsFileStatus(
        node.isDirectory() ? 0 : ((INodeFile)node).computeContentSummary().getLength(), 
        node.isDirectory(), 
        node.isDirectory() ? 0 : ((INodeFile)node).getFileReplication(), 
        node.isDirectory() ? 0 : ((INodeFile)node).getPreferredBlockSize(),
        node.getModificationTime(snapshot),
        node.getAccessTime(snapshot),
        node.getFsPermission(snapshot),
        node.getUserName(snapshot),
        node.getGroupName(snapshot),
        path);
  }
  
  /**
   * Caches frequently used file names to reuse file name objects and
   * reduce heap size.
   */
  void cacheName(INode inode) {
    // Name is cached only for files
    if (!inode.isFile()) {
      return;
    }
    ByteArray name = new ByteArray(inode.getLocalNameBytes());
    name = nameCache.put(name);
    if (name != null) {
      inode.setLocalName(name.getBytes());
    }
  }
}
