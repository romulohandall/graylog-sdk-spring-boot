package com.joonsang.graylog.sdk.spring.starter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.jayway.jsonpath.JsonPath;
import com.joonsang.graylog.sdk.spring.starter.GraylogRequest;
import com.joonsang.graylog.sdk.spring.starter.autoconfigure.GraylogApiProperties;
import com.joonsang.graylog.sdk.spring.starter.constant.SearchTypePivotType;
import com.joonsang.graylog.sdk.spring.starter.constant.SearchTypeType;
import com.joonsang.graylog.sdk.spring.starter.constant.SeriesType;
import com.joonsang.graylog.sdk.spring.starter.domain.*;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Search
 * @author debugrammer
 * @since 2.0.0
 */
public class Search {

    private static final MediaType CONTENT_TYPE_JSON = MediaType.get("application/json; charset=utf-8");

    private final GraylogRequest graylogRequest;

    private final GraylogApiProperties graylogApiProperties;

    private final ObjectMapper objectMapper;

    public Search(
        GraylogRequest graylogRequest,
        GraylogApiProperties graylogApiProperties,
        ObjectMapper objectMapper
    ) {

        this.graylogRequest = graylogRequest;
        this.graylogApiProperties = graylogApiProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Message list search.
     * @param timerange Graylog time range object
     * @param searchQuery Graylog search query
     * @param limit maximum number of messages to return
     * @param offset offset
     * @param sort Graylog sort config object
     * @param streamIds Graylog stream ID list
     * @return Message list from Graylog
     * @throws IOException Graylog server failure
     * @since 2.0.0
     */
    public MessageList getMessages(
        Timerange timerange,
        String searchQuery,
        int limit,
        int offset,
        SortConfig sort,
        List<String> streamIds
    ) throws IOException {

        SearchType searchType = SearchType.builder()
            .name("messages")
            .limit(limit)
            .offset(offset)
            .sort(sort == null ? List.of() : List.of(sort))
            .type(SearchTypeType.messages)
            .build();

        Query query = Query.builder()
            .filter(convertToFilter(streamIds))
            .query(SearchQuery.builder().queryString(searchQuery).build())
            .timerange(timerange)
            .searchType(searchType)
            .build();

        String body = syncSearch(SearchSpec.builder().query(query).build());

        String searchResultPath = "$.results." + query.getId() + ".search_types." + searchType.getId();

        @SuppressWarnings("unchecked")
        List<Map<String, Map<String, ?>>> messages = JsonPath.parse(body).read(searchResultPath + ".messages", List.class);
        Integer totalCount = JsonPath.parse(body).read(searchResultPath + ".total_results", Integer.class);

        return MessageList.builder()
            .messages(messages)
            .totalCount(totalCount)
            .build();
    }

    /**
     * Statistics.
     * @param timerange Graylog time range object
     * @param searchQuery Graylog search query
     * @param seriesList Gralog series object list
     * @param streamIds Graylog stream ID list
     * @return Statistics from Graylog
     * @throws IOException Graylog server failure
     * @since 2.0.0
     */
    public List<Statistics> getStatistics(
        Timerange timerange,
        String searchQuery,
        List<Series> seriesList,
        List<String> streamIds
    ) throws IOException {

        SearchType searchType = SearchType.builder()
            .name("chart")
            .series(seriesList)
            .rollup(true)
            .rowGroups(List.of())
            .columnGroups(List.of())
            .sort(List.of())
            .type(SearchTypeType.pivot)
            .build();

        Query query = Query.builder()
            .filter(convertToFilter(streamIds))
            .query(SearchQuery.builder().queryString(searchQuery).build())
            .timerange(timerange)
            .searchType(searchType)
            .build();

        String body = syncSearch(SearchSpec.builder().query(query).build());

        String searchQueryPath = "$.results." + query.getId() + ".query.search_types[0].series";
        String searchResultPath = "$.results." + query.getId() + ".search_types." + searchType.getId() + ".rows[0].values";

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> requestSeries = JsonPath.parse(body).read(searchQueryPath, List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> values = JsonPath.parse(body).read(searchResultPath, List.class);

        Map<String, Value> converted = convertToValueMap(requestSeries, values).get("row-leaf").get(StringUtils.EMPTY);

        return converted.values().stream()
            .map(Value::getStatistics)
            .collect(Collectors.toList());
    }

    /**
     * Terms.
     * @param timerange Graylog time range object
     * @param searchQuery Graylog search query
     * @param seriesList Gralog series object list
     * @param rowGroups Graylog search type pivot object list
     * @param columnGroups Graylog search type pivot object list
     * @param sort Graylog sort config object
     * @param streamIds Graylog stream ID list
     * @return Terms from Graylog
     * @throws IOException Graylog server failure
     * @since 2.0.0
     */
    public Terms getTerms(
        Timerange timerange,
        String searchQuery,
        List<Series> seriesList,
        List<SearchTypePivot> rowGroups,
        List<SearchTypePivot> columnGroups,
        SortConfig sort,
        List<String> streamIds
    ) throws IOException {

        SearchType searchType = SearchType.builder()
            .name("chart")
            .series(seriesList)
            .rollup(true)
            .rowGroups(rowGroups)
            .columnGroups(columnGroups)
            .sort(sort == null ? List.of() : List.of(sort))
            .type(SearchTypeType.pivot)
            .build();

        Query query = Query.builder()
            .filter(convertToFilter(streamIds))
            .query(SearchQuery.builder().queryString(searchQuery).build())
            .timerange(timerange)
            .searchType(searchType)
            .build();

        String body = syncSearch(SearchSpec.builder().query(query).build());

        String searchQueryPath = "$.results." + query.getId() + ".query.search_types[0].series";
        String searchResultPath = "$.results." + query.getId() + ".search_types." + searchType.getId() + ".rows";

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> requestSeries = JsonPath.parse(body).read(searchQueryPath, List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> results = JsonPath.parse(body).read(searchResultPath, List.class);

        List<Terms.TermsData> termsDataList = new ArrayList<>();

        for (Map<String, ?> result : results) {
            if (!result.get("source").equals("leaf")) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) result.get("key");

            @SuppressWarnings("unchecked")
            List<Map<String, ?>> values = (List<Map<String, ?>>) result.get("values");

            Map<String, Map<String, Map<String, Value>>> valueMap = convertToValueMap(requestSeries, values);

            List<Statistics> statsList = valueMap.get("row-leaf").get(StringUtils.EMPTY).values().stream()
                .map(Value::getStatistics)
                .collect(Collectors.toList());

            List<Terms.StackedColumn> stackedColumns = new ArrayList<>();

            for (Map<String, Value> colLeaf : valueMap.get("col-leaf").values()) {
                stackedColumns.add(
                    Terms.StackedColumn.builder()
                        .columnLabels(colLeaf.get(colLeaf.keySet().iterator().next()).getLabels())
                        .statisticsList(
                            colLeaf.values().stream()
                                .map(Value::getStatistics)
                                .collect(Collectors.toList())
                        )
                        .build()
                );
            }

            termsDataList.add(
                Terms.TermsData.builder()
                    .baseLabels(labels)
                    .statisticsList(statsList)
                    .stackedColumns(stackedColumns)
                    .build()
            );
        }

        return Terms.builder().terms(termsDataList).build();
    }

    /**
     * Histogram.
     * @param timerange Graylog time range object
     * @param interval Graylog interval object
     * @param searchQuery Graylog search query
     * @param seriesList Gralog series object list
     * @param columnGroups Graylog search type pivot object list
     * @param streamIds Graylog stream ID list
     * @return Histogram from Graylog
     * @throws IOException Graylog server failure
     * @since 2.0.0
     */
    public Histogram getHistogram(
        Timerange timerange,
        Interval interval,
        String searchQuery,
        List<Series> seriesList,
        List<SearchTypePivot> columnGroups,
        List<String> streamIds
    ) throws IOException {

        SearchType searchType = SearchType.builder()
            .name("chart")
            .series(seriesList)
            .rollup(true)
            .rowGroups(
                List.of(
                    SearchTypePivot.builder()
                        .type(SearchTypePivotType.time)
                        .field("timestamp")
                        .interval(interval)
                        .build()
                )
            )
            .columnGroups(columnGroups)
            .sort(List.of())
            .type(SearchTypeType.pivot)
            .build();

        Query query = Query.builder()
            .filter(convertToFilter(streamIds))
            .query(SearchQuery.builder().queryString(searchQuery).build())
            .timerange(timerange)
            .searchType(searchType)
            .build();

        String body = syncSearch(SearchSpec.builder().query(query).build());

        String searchQueryPath = "$.results." + query.getId() + ".query.search_types[0].series";
        String searchResultPath = "$.results." + query.getId() + ".search_types." + searchType.getId() + ".rows";

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> requestSeries = JsonPath.parse(body).read(searchQueryPath, List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, ?>> results = JsonPath.parse(body).read(searchResultPath, List.class);

        List<Histogram.HistogramData> histogramDataList = new ArrayList<>();

        for (Map<String, ?> result : results) {
            if (!result.get("source").equals("leaf")) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) result.get("key");

            @SuppressWarnings("unchecked")
            List<Map<String, ?>> values = (List<Map<String, ?>>) result.get("values");

            String timestamp = CollectionUtils.isEmpty(labels) ? StringUtils.EMPTY : labels.get(0);

            Map<String, Map<String, Map<String, Value>>> valueMap = convertToValueMap(requestSeries, values);

            List<Statistics> statsList = valueMap.get("row-leaf").get(StringUtils.EMPTY).values().stream()
                .map(Value::getStatistics)
                .collect(Collectors.toList());

            List<Histogram.StackedColumn> stackedColumns = new ArrayList<>();

            for (Map<String, Value> colLeaf : valueMap.get("col-leaf").values()) {
                stackedColumns.add(
                    Histogram.StackedColumn.builder()
                        .columnLabels(colLeaf.get(colLeaf.keySet().iterator().next()).getLabels())
                        .statisticsList(
                            colLeaf.values().stream()
                                .map(Value::getStatistics)
                                .collect(Collectors.toList())
                        )
                        .build()
                );
            }

            histogramDataList.add(
                Histogram.HistogramData.builder()
                    .baseLabel(timestamp)
                    .statisticsList(statsList)
                    .stackedColumns(stackedColumns)
                    .build()
            );
        }

        return Histogram.builder().histogram(histogramDataList).build();
    }

    /**
     * Perform synchronous search.
     * @param searchSpec Graylog search spec object
     * @return Response body from Graylog
     * @throws IOException Graylog server failure
     * @since 2.0.0
     */
    public String syncSearch(SearchSpec searchSpec) throws IOException {
        String requestJson = objectMapper.writeValueAsString(searchSpec);
        RequestBody jsonBody = RequestBody.create(requestJson, CONTENT_TYPE_JSON);

        HttpUrl httpUrl = graylogRequest.getHttpUrlBuilder()
            .addPathSegments("api/views/search/sync")
            .addQueryParameter("timeout", String.valueOf(graylogApiProperties.getTimeout()))
            .build();

        return graylogRequest.httpPostRequest(httpUrl, jsonBody);
    }

    /**
     * Convert Graylog response to Value map.
     * @param requestSeries series requested to Graylog
     * @param values values from Graylog
     * @return Value map
     * @since 2.0.0
     */
    private Map<String, Map<String, Map<String, Value>>> convertToValueMap(
        List<Map<String, ?>> requestSeries,
        List<Map<String, ?>> values
    ) {

        Map<String, Map<String, String>> keyMap = requestSeries.stream()
            .collect(
                Collectors.toMap(
                    series -> (String) series.get("id"),
                    series -> Map.of(
                        "field", Objects.requireNonNullElse((String) series.get("field"), StringUtils.EMPTY),
                        "type", (String) series.get("type"),
                        "percentile", String.valueOf((series.get("percentile")))
                    )
                )
            );

        Map<String, Map<String, Map<String, Value>>> leafFieldValueMap = ImmutableMap.of(
            "row-leaf", new HashMap<>(),
            "col-leaf", new HashMap<>()
        );

        for (Map<String, ?> valueMap : values) {
            String source = (String) valueMap.get("source");

            if (List.of("row-leaf", "col-leaf").stream().noneMatch(source::equals)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) valueMap.get("key");

            List<String> columnLabels = new ArrayList<>();
            StringBuilder keyName = new StringBuilder();
            String id = StringUtils.EMPTY;

            for (String key : keys) {
                if (keyMap.containsKey(key)) {
                    id = key;
                    continue;
                }

                columnLabels.add(key);
                keyName.append(key);
            }

            String field = keyMap.get(id).get("field");
            String type = keyMap.get(id).get("type");
            String percentile = keyMap.get(id).get("percentile");

            if (!leafFieldValueMap.get(source).containsKey(keyName.toString())) {
                leafFieldValueMap.get(source).put(keyName.toString(), new HashMap<>());
            }

            if (!leafFieldValueMap.get(source).get(keyName.toString()).containsKey(field)) {
                leafFieldValueMap.get(source).get(keyName.toString()).put(field, new Value());
                leafFieldValueMap.get(source).get(keyName.toString()).get(field).setLabels(columnLabels);
                leafFieldValueMap.get(source).get(keyName.toString()).get(field).setStatistics(new Statistics());
                leafFieldValueMap.get(source).get(keyName.toString()).get(field).getStatistics().setField(field);
            }

            Statistics statistics = leafFieldValueMap.get(source).get(keyName.toString()).get(field).getStatistics();

            if (type.equals(SeriesType.avg.toString())) {
                statistics.setAverage((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.card.toString())) {
                statistics.setCardinality((Integer) valueMap.get("value"));
            } else if (type.equals(SeriesType.count.toString())) {
                statistics.setCount((Integer) valueMap.get("value"));
            } else if (type.equals(SeriesType.max.toString())) {
                statistics.setMax((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.min.toString())) {
                statistics.setMin((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.stddev.toString())) {
                statistics.setStdDeviation((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.sum.toString())) {
                statistics.setSum((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.sumofsquares.toString())) {
                statistics.setSum((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.variance.toString())) {
                statistics.setSum((Double) valueMap.get("value"));
            } else if (type.equals(SeriesType.percentile.toString())) {
                if (statistics.getPercentiles() == null) {
                    statistics.setPercentiles(new ArrayList<>());
                }

                if (statistics.getPercentileRanks() == null) {
                    statistics.setPercentileRanks(new ArrayList<>());
                }

                statistics.getPercentiles().add((Double) valueMap.get("value"));
                statistics.getPercentileRanks().add(percentile);
            }
        }

        return leafFieldValueMap;
    }

    /**
     * Convert stream ID list to Graylog filter object.
     * @param streamIds Graylog stream ID list
     * @return Graylog filter object
     * @since 2.0.0
     */
    private Filter convertToFilter(List<String> streamIds) {
        List<SearchFilter> filters = streamIds.stream()
            .map(streamId -> SearchFilter.builder().id(streamId).build())
            .collect(Collectors.toList());

        return Filter.builder().filters(filters).build();
    }
}
