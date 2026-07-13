package nosql.common;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 服务端响应传输对象
 */
public class Response implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private DbValue value;
    private Map<String, DbValue> bulkData;
    private List<String> keys;

    public Response() {}

    public Response(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public Response(boolean success, String message, DbValue value) {
        this.success = success;
        this.message = message;
        this.value = value;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
        return "Response{success=" + success + ", message='" + message + "', value=" + value + "}";
    }
}
