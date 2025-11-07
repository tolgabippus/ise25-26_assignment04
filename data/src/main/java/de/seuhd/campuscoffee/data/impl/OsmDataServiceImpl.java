package de.seuhd.campuscoffee.data.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OSM import service that fetches node data from the OpenStreetMap API.
 * Uses RestClient for HTTP communication and Jackson XML for parsing responses.
 */
@Service
@Slf4j
class OsmDataServiceImpl implements OsmDataService {

    private static final String OSM_API_BASE_URL = "https://www.openstreetmap.org/api/0.6";
    private final RestClient restClient;
    private final XmlMapper xmlMapper;

    public OsmDataServiceImpl() {
        this.restClient = RestClient.builder()
                .baseUrl(OSM_API_BASE_URL)
                .build();

        this.xmlMapper = new XmlMapper();
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public @NonNull OsmNode fetchNode(@NonNull Long nodeId) throws OsmNodeNotFoundException {
        log.info("Fetching OSM node {} from OpenStreetMap API", nodeId);

        try {
            String xmlResponse = restClient.get()
                    .uri("/node/{id}.xml", nodeId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.error("OSM node {} not found (HTTP {})", nodeId, response.getStatusCode());
                        throw new OsmNodeNotFoundException(nodeId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        log.error("OSM API server error for node {} (HTTP {})", nodeId, response.getStatusCode());
                        throw new OsmNodeNotFoundException(nodeId);
                    })
                    .body(String.class);

            if (xmlResponse == null || xmlResponse.isEmpty()) {
                log.error("Empty response from OSM API for node {}", nodeId);
                throw new OsmNodeNotFoundException(nodeId);
            }

            // Parse XML response
            OsmApiResponse apiResponse = xmlMapper.readValue(xmlResponse, OsmApiResponse.class);

            if (apiResponse.node == null) {
                log.error("No node element found in OSM API response for node {}", nodeId);
                throw new OsmNodeNotFoundException(nodeId);
            }

            // Convert tags list to map
            Map<String, String> tagsMap = new HashMap<>();
            if (apiResponse.node.tags != null) {
                for (OsmTag tag : apiResponse.node.tags) {
                    tagsMap.put(tag.key, tag.value);
                }
            }

            OsmNode osmNode = OsmNode.builder()
                    .nodeId(apiResponse.node.id)
                    .lat(apiResponse.node.lat)
                    .lon(apiResponse.node.lon)
                    .tags(tagsMap)
                    .build();

            log.info("Successfully fetched OSM node {} with {} tags", nodeId, tagsMap.size());
            return osmNode;

        } catch (OsmNodeNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching OSM node {}: {}", nodeId, e.getMessage(), e);
            throw new OsmNodeNotFoundException(nodeId);
        }
    }

    /**
     * DTO for OSM API XML response root element.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OsmApiResponse {
        @JsonProperty("node")
        public OsmNodeElement node;
    }

    /**
     * DTO for OSM node element in XML response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OsmNodeElement {
        @JacksonXmlProperty(isAttribute = true)
        public Long id;

        @JacksonXmlProperty(isAttribute = true)
        public Double lat;

        @JacksonXmlProperty(isAttribute = true)
        public Double lon;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "tag")
        public List<OsmTag> tags;
    }

    /**
     * DTO for OSM tag element in XML response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OsmTag {
        @JacksonXmlProperty(isAttribute = true, localName = "k")
        public String key;

        @JacksonXmlProperty(isAttribute = true, localName = "v")
        public String value;
    }
}
