package example.api.indices;

import example.api.client.ClientFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;

import java.io.IOException;

public class IndicesApi {

    private RestHighLevelClient client;

    public IndicesApi(){
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

    public void deleteIndices(String name){

    }


    public void updateIndices(String name){

    }

    public void openIndices(String name){

    }

    public void closeIndices(String name){

    }
}
