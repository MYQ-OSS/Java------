package nosql.common;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 客户端请求传输对象
 */
public class Request implements Serializable {
    private static final long serialVersionUID = 1L;

    private String collection;
    private String key;
    private DbValue value;
    private Map<String, DbValue> bulkData; // 用于批量操作
    private List<String> keys;             // 用于批量获取/删除

    public Request() {}

    // 单个操作构造
    public Request(String collection, String key, DbValue value) {
        this.collection = collection;
        this.key = key;
        this.value = value;
    }

    // 批量操作构造
    public Request(String collection, Map<String, DbValue> bulkData) {
        this.collection = collection;
        this.bulkData = bulkData;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public DbValue getValue() {
        return value;
    }

    public void setValue(DbValue value) {
        this.value = value;
    }

    public Map<String, DbValue> getBulkData() {
        return bulkData;
    }

    public void setBulkData(Map<String, DbValue> bulkData) {
        this.bulkData = bulkData;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    @Override
    public String toString() {
        return "Request{collection='" + collection + "', key='" + key + "', value=" + value + ", bulkDataSize=" + (bulkData != null ? bulkData.size() : 0) + "}";
    }
}
