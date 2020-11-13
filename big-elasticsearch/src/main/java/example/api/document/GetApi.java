package example.api.document;

import example.api.client.ClientFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;

/**
 * Elasticsearch Get Api 提供文档查询
 */
public class GetApi {

    private final RestHighLevelClient client;

    public GetApi(){
        client = ClientFactory.restHighLevelClient();
    }

    // 获取指定 id 文档
    private GetRequest getRequest(String index, String docId){
        GetRequest request = new GetRequest(index);
        if (Strings.hasLength(docId)){
            request = new GetRequest(index, docId);
        }
        return request.fetchSourceContext(FetchSourceContext.DO_NOT_FETCH_SOURCE)
                .storedFields("message")
                .routing("routing")
                .preference("preference")
                .realtime(false)
                .refresh(false)
                .version(4)
                .versionType(VersionType.EXTERNAL);
    }

    // 获取所有文档
    private GetRequest getRequest(String index){
        return getRequest(index, null);
    }

    public void syncGet(String index, String docId) throws IOException {

        GetRequest request = getRequest(index, docId);
        GetResponse response = client.get(request, RequestOptions.DEFAULT);
        processResponse(response);
    }

    public void asyncGet(String index, String docId){

        final GetRequest request = getRequest(index, docId);
        client.getAsync(request, RequestOptions.DEFAULT, new ActionListener<>() {
            @Override
            public void onResponse(GetResponse response) {
                processResponse(response);
            }

            @Override
            public void onFailure(Exception e) {}
        });
    }

    public void checkExists(String index, String doc) throws IOException {
        GetRequest request = getRequest(index, doc);
        // 禁止提取 _source
        request.fetchSourceContext(new FetchSourceContext(false));
        // 显式设置需要返回的字段，默认返回 _source
        request.storedFields("_none_");

        boolean exists = client.exists(request, RequestOptions.DEFAULT);
        System.out.println("index: " + index + ", doc: " + doc + ", exists: " + exists);

        client.existsAsync(request, RequestOptions.DEFAULT, new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean exists) {
                System.out.println("index: " + index + ", doc: " + doc + ", exists: " + exists);
            }

            @Override
            public void onFailure(Exception e) {

            }
        });
    }

    private void processResponse(GetResponse response){
        String index = response.getIndex();
        String id = response.getId();
        System.out.println(index + ", " + id);
        if (response.isExists()){
            long version = response.getVersion();
            String source = response.getSourceAsString();
            System.out.println(version + ", " + source);
        }
    }

    public static void main(String[] args) throws IOException {

        new GetApi().syncGet("posts", "eI50uE3gR6qkz_Qck8XTBw");
    }
}
