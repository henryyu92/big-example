package example.api.document;

import example.api.client.ClientFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.VersionType;

import java.io.IOException;

public class DocumentApi {

    private final RestHighLevelClient client;

    public DocumentApi(){
        client = ClientFactory.restHighLevelClient();
    }

    // 创建文档
    public void addDocument(String indexName, String document) throws IOException {

        final IndexRequest request = Requests.indexRequest(indexName)
                .timeout(TimeValue.timeValueMinutes(10))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
                .versionType(VersionType.EXTERNAL)
                .opType(DocWriteRequest.OpType.CREATE)
                .setPipeline("pipeline");



    }

    public static void main(String[] args) throws IOException {
        new DocumentApi().addDocument("test", "");
    }
}
