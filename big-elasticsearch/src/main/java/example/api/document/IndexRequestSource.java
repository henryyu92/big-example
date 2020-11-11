package example.api.document;

import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;

import java.io.IOException;
import java.util.Map;

public class IndexRequestSource {

    // 使用 json 格式提供文档的 source
    public IndexRequest jsonSourceIndexRequest(String index, String document, String json){

        IndexRequest request = indexRequest(index, document);
        request.source(json, XContentType.JSON);

        return request;
    }

    // map 方式提供文档的 source
    public IndexRequest mapSourceIndexRequest(String index, String document, Map<String, Object> map){

        IndexRequest request = indexRequest(index, document);
        request.source(map);

        return request;
    }

    // XContentBuilder 方式提供文档的 source
    public IndexRequest xContentTypeBuilderSourceIndexRequest(String index, String document, Map<String, Object> map) throws IOException {

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder();
        xContentBuilder.startObject();
        map.forEach((key, value) -> {
            try {
                xContentBuilder.field(key).value(value);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        xContentBuilder.endObject();

        IndexRequest request = indexRequest(index, document);
        request.source(xContentBuilder);

        return request;
    }


    private IndexRequest indexRequest(String index, String document){
        return Requests.indexRequest(index)
                .id(document)
                // 路由
                .routing("routing")
                // 超时时间
                .timeout(TimeValue.timeValueSeconds(1))
                // 超时策略
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
                // 版本类型
                .versionType(VersionType.INTERNAL)
                // 操作类型
                .opType(DocWriteRequest.OpType.CREATE)
                ;
    }

}
