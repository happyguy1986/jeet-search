package canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * canal客户端服务
 *
 * @author bug1024
 * @date 2017-03-25
 */
public class CanalService {

    private CanalMsgHandler canalMsgHandler;

    private CanalPool canalPool;

    public CanalService(CanalPool canalPool, CanalMsgHandler canalMsgHandler) {
        this.canalPool  = canalPool;
        this.canalMsgHandler = canalMsgHandler;
    }

    public void start() {
        int batchSize = 1000;
        int emptyCount = 0;

        CanalConnector canalConnector = canalPool.getConnector();

        try {
            canalConnector.connect();
            canalConnector.subscribe(".*\\..*");
            canalConnector.rollback();
            int totalEmptyCount = 300;

            System.out.println("=======Begin=======");

            while (emptyCount < totalEmptyCount) {
                // 获取指定数量的数据
                Message message = canalConnector.getWithoutAck(batchSize);
                long batchId = message.getId();
                int size = message.getEntries().size();
                if (batchId == -1 || size == 0) {
                    emptyCount++;
                    System.out.println(emptyCount);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {

                    }
                } else {
                    emptyCount = 0;
                    processEntry(message.getEntries());
                }

                // 提交确认
                canalConnector.ack(batchId);
                // 处理失败, 回滚数据
                // connector.rollback(batchId);
            }

            System.out.println("=======End=======");
        } finally {
            canalConnector.disconnect();
        }
    }

    private void processEntry(List<CanalEntry.Entry> entries) {
        List<CanalMsg> msgList = convertToCanalMsgList(entries);
        for (CanalMsg msg : msgList) {
            canalMsgHandler.handle(msg);
        }
    }

    private List<CanalMsg> convertToCanalMsgList(List<CanalEntry.Entry> entries) {
        List<CanalMsg> msgList = new ArrayList<CanalMsg>();
        CanalMsgContent canalMsgContent = null;
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN || entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                continue;
            }

            CanalEntry.RowChange rowChange = null;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                throw new RuntimeException("ERROR ## parse error, data:" + entry.toString(), e);
            }

            CanalEntry.EventType eventType = rowChange.getEventType();
            canalMsgContent = new CanalMsgContent();
            canalMsgContent.setBinLogFile(entry.getHeader().getLogfileName());
            canalMsgContent.setBinlogOffset(entry.getHeader().getLogfileOffset());
            canalMsgContent.setDbName(entry.getHeader().getSchemaName());
            canalMsgContent.setTableName(entry.getHeader().getTableName());
            canalMsgContent.setEventType(eventType.toString().toLowerCase());

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                canalMsgContent.setDataBefore(convertToCanalChangeInfoList(rowData.getBeforeColumnsList()));
                canalMsgContent.setDataAfter(convertToCanalChangeInfoList(rowData.getAfterColumnsList()));
                CanalMsg canalMsg = new CanalMsg(canalMsgContent);
                msgList.add(canalMsg);
            }
        }

        return msgList;
    }

    private List<CanalChangeInfo> convertToCanalChangeInfoList(List<CanalEntry.Column> columnList) {
        List<CanalChangeInfo> canalChangeInfoList = new ArrayList<CanalChangeInfo>();
        for (CanalEntry.Column column : columnList) {
            CanalChangeInfo canalChangeInfo = new CanalChangeInfo();
            canalChangeInfo.setName(column.getName());
            canalChangeInfo.setValue(column.getValue());
            canalChangeInfo.setUpdate(column.getUpdated());
            canalChangeInfoList.add(canalChangeInfo);
        }

        return canalChangeInfoList;
    }

}
