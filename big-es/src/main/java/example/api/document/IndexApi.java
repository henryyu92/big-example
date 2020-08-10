package example.api.document;

import example.api.client.ClientFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class IndexApi {

    private RestHighLevelClient client;

    public IndexApi(){
        client = ClientFactory.restHighLevelClient();
    }

    public void addIndices(String name) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest(name);

        request.settings(Settings.builder().put("index.number_of_shards", 3).put("index.number_of_replicas", 2));

        request.alias(new Alias("test"));
        request.setTimeout(TimeValue.timeValueMinutes(10));
        request.setMasterTimeout(TimeValue.timeValueMinutes(30));
        request.waitForActiveShards(ActiveShardCount.from(2));

        RequestOptions options = RequestOptions.DEFAULT;

        client.indices().create(request, options);

        // 异步提交
        client.indices().createAsync(request, options, new ActionListener<CreateIndexResponse>() {
            @Override
            public void onResponse(CreateIndexResponse createIndexResponse) {

            }

            @Override
            public void onFailure(Exception e) {

            }
        });

    }

    public void addDoc() throws IOException {

        IndexRequest request = indexRequest("posts", DocWriteRequest.OpType.INDEX);

        // 同步提交
        final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
        System.out.println(response);

        // 异步提交
        client.indexAsync(request, RequestOptions.DEFAULT, new ActionListener<IndexResponse>() {
            @Override
            public void onResponse(IndexResponse indexResponse) {
                System.out.println(indexResponse);
            }

            @Override
            public void onFailure(Exception e) {
                e.printStackTrace();
            }
        });
    }

    private IndexRequest indexRequest(String index, DocWriteRequest.OpType opType){
        IndexRequest request = Requests.indexRequest(index)
                .id("1")
                .routing("routing")
                .timeout(TimeValue.timeValueSeconds(1))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
//                .version(2)
                .versionType(VersionType.INTERNAL)
                .opType(opType)
//                .setPipeline("pipeline")
                ;

        jsonSource(request);

        return request;
    }


    // 使用 json 格式提供文档的 source
    private void jsonSource(IndexRequest request){

        String json = "{" +
                "\"user\":\"kimchy\"," +
                "\"postDate\":\"2013-01-30\"," +
                "\"message\":\"trying out Elasticsearch\"" +
                "}";
        request.source(json, XContentType.JSON);
    }

    // map 方式提供文档的 source
    private void mapSource(IndexRequest request){
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("user", "kimchy");
        jsonMap.put("postDate", new Date());
        jsonMap.put("message", "trying out Elasticsearch");
        IndexRequest indexRequest = new IndexRequest("posts");

        request.source(jsonMap);
    }

    // XContentBuilder 方式提供文档的 source
    private void xContentBuilderSource(IndexRequest request) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        {
            builder.field("user", "kimchy");
            builder.timeField("postDate", new Date());
            builder.field("message", "trying out Elasticsearch");
        }
        builder.endObject();

        request.source(builder);
    }

    // kv 方式提供文档的 source
    private void kvSource(IndexRequest request){
        request.source("user", "kimchy",
                "postDate", new Date(),
                "message", "trying out Elasticsearch");
    }

    public static void main(String[] args) throws IOException {
        new IndexApi().addDoc();
    }
}
