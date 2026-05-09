package com.yupi.yuaiagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网页搜索工具
 */
public class WebSearchTool {

    // SearchAPI 的搜索接口地址
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

   @Tool(description = "Search for information from Baidu Search Engine")
   public String searchWeb2(
           @ToolParam(description = "Search query keyword") String query) {
       Map<String, Object> paramMap = new HashMap<>();
       paramMap.put("q", query);
       paramMap.put("api_key", apiKey);
       paramMap.put("engine", "baidu");
       try {
           String response = HttpUtil.get(SEARCH_API_URL, paramMap);
           // 取出返回结果的前 5 条
           JSONObject jsonObject = JSONUtil.parseObj(response);
           // 提取 organic_results 部分
           JSONArray organicResults = jsonObject.getJSONArray("organic_results");
           List<Object> objects = organicResults.subList(0, 5);
           // 拼接搜索结果为字符串
           String result = objects.stream().map(obj -> {
               JSONObject tmpJSONObject = (JSONObject) obj;
               return tmpJSONObject.toString();
           }).collect(Collectors.joining(","));
           return result;
       } catch (Exception e) {
           return "Error searching Baidu: " + e.getMessage();
       }
   }
    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            List<Object> topResults = organicResults.subList(0, Math.min(5, organicResults.size()));

            // 只提取 title, link, snippet
            String result = topResults.stream().map(obj -> {
                JSONObject tmp = (JSONObject) obj;
                String title = tmp.getStr("title");
//                String link = tmp.getStr("link");
                String snippet = tmp.getStr("snippet");
                return String.format("Title: %s\nSnippet: %s", title, snippet);
            }).collect(Collectors.joining("\n\n"));

            return result;
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }

}
