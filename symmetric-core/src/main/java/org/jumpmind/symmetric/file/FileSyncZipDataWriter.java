/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.model.Table;
import org.jumpmind.exception.IoException;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.io.data.Batch;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.DataContext;
import org.jumpmind.symmetric.io.data.DataEventType;
import org.jumpmind.symmetric.io.data.IDataWriter;
import org.jumpmind.symmetric.io.stage.IStagedResource;
import org.jumpmind.symmetric.model.FileSnapshot;
import org.jumpmind.symmetric.model.FileSnapshot.LastEventType;
import org.jumpmind.symmetric.model.FileTrigger;
import org.jumpmind.symmetric.model.FileTriggerRouter;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.service.IFileSyncService;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.util.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSyncZipDataWriter implements IDataWriter {

    static final Logger log = LoggerFactory.getLogger(FileSyncZipDataWriter.class);

    protected long byteCount;
    protected long maxBytesToSync;
    protected IFileSyncService fileSyncService;
    protected IStagedResource stagedResource;
    protected ZipOutputStream zos;
    protected Table snapshotTable;
    protected Batch batch;
    protected Map<Batch, Statistics> statistics = new HashMap<Batch, Statistics>();
    protected List<FileSnapshot> snapshotEvents;
    protected DataContext context;
    protected INodeService nodeService;

    public FileSyncZipDataWriter(long maxBytesToSync, IFileSyncService fileSyncService,
            INodeService nodeService, IStagedResource stagedResource) {
        this.maxBytesToSync = maxBytesToSync;
        this.fileSyncService = fileSyncService;
        this.stagedResource = stagedResource;
        this.nodeService = nodeService;
    }

    public void open(DataContext context) {
        this.context = context;
    }

    @Override
    public void close() {
        // no-op as this is called at batch boundaries, but this writer can handle multiple batches.
    }

    public Map<Batch, Statistics> getStatistics() {
        return statistics;
    }

    public void start(Batch batch) {
        this.batch = batch;
        this.statistics.put(batch, new Statistics());
        this.snapshotEvents = new ArrayList<FileSnapshot>();
    }

    public boolean start(Table table) {
        this.snapshotTable = table;
        return true;
    }

    public void write(CsvData data) {
        DataEventType eventType = data.getDataEventType();
        if (eventType == DataEventType.INSERT || eventType == DataEventType.UPDATE) {
            Map<String, String> columnData = data.toColumnNameValuePairs(
                    snapshotTable.getColumnNames(), CsvData.ROW_DATA);
            Map<String, String> oldColumnData = data.toColumnNameValuePairs(
                    snapshotTable.getColumnNames(), CsvData.OLD_DATA);
     
            FileSnapshot snapshot = new FileSnapshot();
            snapshot.setTriggerId(columnData.get("TRIGGER_ID"));
            snapshot.setRouterId(columnData.get("ROUTER_ID"));
            snapshot.setFileModifiedTime(Long.parseLong(columnData.get("FILE_MODIFIED_TIME")));
            snapshot.setCrc32Checksum(Long.parseLong(columnData.get("CRC32_CHECKSUM")));
            String oldChecksum = oldColumnData.get("CRC32_CHECKSUM");
            if (StringUtils.isNotBlank(oldChecksum)) {
                snapshot.setOldCrc32Checksum(Long.parseLong(oldChecksum));
            }
            snapshot.setFileSize(Long.parseLong(columnData.get("FILE_SIZE")));
            snapshot.setLastUpdateBy(columnData.get("LAST_UPDATE_BY"));
            snapshot.setFileName(columnData.get("FILE_NAME"));
            snapshot.setRelativeDir(columnData.get("RELATIVE_DIR"));
            snapshot.setLastEventType(LastEventType.fromCode(columnData.get("LAST_EVENT_TYPE")));
            snapshotEvents.add(snapshot);
        } else if (eventType == DataEventType.RELOAD) {
            String targetNodeId = context.getBatch().getTargetNodeId();
            Node targetNode = nodeService.findNode(targetNodeId);
            List<FileTriggerRouter> fileTriggerRouters = fileSyncService
                    .getFileTriggerRoutersForCurrentNode();
            for (FileTriggerRouter fileTriggerRouter : fileTriggerRouters) {
                if (fileTriggerRouter.isEnabled()
                        && fileTriggerRouter.isInitialLoadEnabled()
                        && fileTriggerRouter.getRouter().getNodeGroupLink().getTargetNodeGroupId()
                                .equals(targetNode.getNodeGroupId())) {
                    DirectorySnapshot directorySnapshot = fileSyncService
                            .getDirectorySnapshot(fileTriggerRouter);
                    snapshotEvents.addAll(directorySnapshot);
                }
            }
        }
    }

    public void end(Table table) {
    }

    public void end(Batch batch, boolean inError) {

        try {
            if (!inError) {
                if (zos == null) {
                    zos = new ZipOutputStream(stagedResource.getOutputStream());
                }
                
                FileSyncZipScript script = createFileSyncZipScript(batch.getTargetNodeId());
                script.buildScriptStart(batch);

                Map<String, LastEventType> entries = new HashMap<String, LastEventType>();
                for (FileSnapshot snapshot : snapshotEvents) {
                    FileTriggerRouter triggerRouter = fileSyncService.getFileTriggerRouter(
                            snapshot.getTriggerId(), snapshot.getRouterId());
                    if (triggerRouter != null) {
                        LastEventType eventType = snapshot.getLastEventType();

                        FileTrigger fileTrigger = triggerRouter.getFileTrigger();

                        String targetBaseDir = ((triggerRouter.getTargetBaseDir()==null)?null:triggerRouter.getTargetBaseDir().replace('\\', '/'));
                        if (StringUtils.isBlank(targetBaseDir)) {
                            targetBaseDir = ((fileTrigger.getBaseDir()==null)?null:fileTrigger.getBaseDir().replace('\\', '/'));
                        }
                        targetBaseDir = StringEscapeUtils.escapeJava(targetBaseDir);

                        StringBuilder entryName = new StringBuilder(Long.toString(batch
                                .getBatchId()));
                        entryName.append("/");
                        if (!snapshot.getRelativeDir().equals(".")) {
                            entryName.append(snapshot.getRelativeDir()).append("/");
                        }
                        entryName.append(snapshot.getFileName());

                        File file = fileTrigger.createSourceFile(snapshot);
                        if (file.isDirectory()) {
                            entryName.append("/");
                        }

                        String targetFile = "targetBaseDir + \"/\" + targetRelativeDir + \"/\" + targetFileName"; 

                        LastEventType previousEventForEntry = entries.get(entryName.toString());
                        boolean process = true;
                        if (previousEventForEntry != null) {
                            if ((previousEventForEntry == eventType)
                                    || (previousEventForEntry == LastEventType.CREATE && eventType == LastEventType.MODIFY)) {
                                process = false;
                            }
                        }
                        
                        if (process) {
                            if (eventType != LastEventType.DELETE) {
                                if (file.exists()) {
                                    byteCount += file.length();
                                    ZipEntry entry = new ZipEntry(entryName.toString());
                                    entry.setSize(file.length());
                                    entry.setTime(file.lastModified());
                                    zos.putNextEntry(entry);
                                    if (file.isFile()) {
                                        FileInputStream fis = new FileInputStream(file);
                                        try {
                                            IOUtils.copy(fis, zos);
                                        } finally {
                                            IOUtils.closeQuietly(fis);
                                        }
                                    }
                                    zos.closeEntry();
                                    entries.put(entryName.toString(), eventType);
                                } else {
                                    log.warn(
                                            "Could not find the {} file to package for synchronization.  Skipping it.",
                                            file.getAbsolutePath());
                                }
                            }

                            script.buildScriptFileSnapshot(batch, snapshot, triggerRouter, fileTrigger, 
                                    file, targetBaseDir, targetFile);
                        }

                    } else {
                        log.error(
                                "Could not locate the file trigger ({}) router ({}) to process a snapshot event.  The event will be ignored",
                                snapshot.getTriggerId(), snapshot.getRouterId());
                    }
                }
                
                script.buildScriptEnd(batch);
                ZipEntry entry = new ZipEntry(batch.getBatchId() + "/" + script.getScriptFileName(batch));
                zos.putNextEntry(entry);
                IOUtils.write(script.getScript().toString(), zos);
                zos.closeEntry();
                
                entry = new ZipEntry(batch.getBatchId() + "/batch-info.txt");
                zos.putNextEntry(entry);
                IOUtils.write(batch.getChannelId(), zos);
                zos.closeEntry();


            }
        } catch (IOException e) {
            throw new IoException(e);
        }

    }

    public void finish() {
        try {
            if (zos != null) {
                zos.finish();
                IOUtils.closeQuietly(zos);
            }
        } catch (IOException e) {
            throw new IoException(e);
        } finally {
            if (stagedResource != null) {                
                stagedResource.close();
                stagedResource.setState(IStagedResource.State.READY);
            }
        }
    }

    public boolean readyToSend() {
        return byteCount > maxBytesToSync;
    }

    protected FileSyncZipScript createFileSyncZipScript(String targetNodeId) {
        if (isCClient(targetNodeId)) {
            return new BashFileSyncZipScript();
        } else {
            return new BeanShellFileSyncZipScript();
        }
    }
    
    protected boolean isCClient(String nodeId) {
        boolean cclient = false;
        Node node = nodeService.findNode(nodeId, true);
        if (node != null) {
            cclient = StringUtils.equals(node.getDeploymentType(), Constants.DEPLOYMENT_TYPE_CCLIENT);
        }
        return cclient;
    }
    
}
