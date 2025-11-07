package de.seuhd.campuscoffee.domain.model;

import lombok.Builder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Represents an OpenStreetMap node with relevant Point of Sale information.
 * This is the domain model for OSM data before it is converted to a POS object.
 *
 * @param nodeId The OpenStreetMap node ID.
 * @param lat The latitude coordinate of the node.
 * @param lon The longitude coordinate of the node.
 * @param tags A map of all OSM tags (key-value pairs) associated with this node.
 */
@Builder
public record OsmNode(
        @NonNull Long nodeId,
        @Nullable Double lat,
        @Nullable Double lon,
        @NonNull Map<String, String> tags
) {
    /**
     * Helper method to safely extract a tag value with a default fallback.
     *
     * @param key the tag key to look up
     * @param defaultValue the value to return if the tag is not present
     * @return the tag value or the default value if not found
     */
    @Nullable
    public String getTag(@NonNull String key, @Nullable String defaultValue) {
        return tags.getOrDefault(key, defaultValue);
    }
}
