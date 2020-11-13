package example.api.document;

import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.VersionType;

/**
 * 删除文档 API
 */
public class DocumentDelete {

    public DeleteRequest getRequest(String index, String doc){
        return Requests.deleteRequest(index)
                .id(doc)
                .routing("routing")
                .timeout(TimeValue.timeValueMinutes(2))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
                .version(2)
                .versionType(VersionType.EXTERNAL);
    }
}
