package de.seuhd.campuscoffee.domain.impl;

import de.seuhd.campuscoffee.domain.exceptions.DuplicatePosNameException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeMissingFieldsException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.CampusType;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.model.Pos;
import de.seuhd.campuscoffee.domain.exceptions.PosNotFoundException;
import de.seuhd.campuscoffee.domain.model.PosType;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import de.seuhd.campuscoffee.domain.ports.PosDataService;
import de.seuhd.campuscoffee.domain.ports.PosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Implementation of the POS service that handles business logic related to POS entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosServiceImpl implements PosService {
    private final PosDataService posDataService;
    private final OsmDataService osmDataService;

    @Override
    public void clear() {
        log.warn("Clearing all POS data");
        posDataService.clear();
    }

    @Override
    public @NonNull List<Pos> getAll() {
        log.debug("Retrieving all POS");
        return posDataService.getAll();
    }

    @Override
    public @NonNull Pos getById(@NonNull Long id) throws PosNotFoundException {
        log.debug("Retrieving POS with ID: {}", id);
        return posDataService.getById(id);
    }

    @Override
    public @NonNull Pos upsert(@NonNull Pos pos) throws PosNotFoundException {
        if (pos.id() == null) {
            // Create new POS
            log.info("Creating new POS: {}", pos.name());
            return performUpsert(pos);
        } else {
            // Update existing POS
            log.info("Updating POS with ID: {}", pos.id());
            // POS ID must be set
            Objects.requireNonNull(pos.id());
            // POS must exist in the database before the update
            posDataService.getById(pos.id());
            return performUpsert(pos);
        }
    }

    @Override
    public @NonNull Pos importFromOsmNode(@NonNull Long nodeId) throws OsmNodeNotFoundException {
        log.info("Importing POS from OpenStreetMap node {}...", nodeId);

        // Fetch the OSM node data using the port
        OsmNode osmNode = osmDataService.fetchNode(nodeId);

        // Convert OSM node to POS domain object and upsert it
        // TODO: Implement the actual conversion (the response is currently hard-coded).
        Pos savedPos = upsert(convertOsmNodeToPos(osmNode));
        log.info("Successfully imported POS '{}' from OSM node {}", savedPos.name(), nodeId);

        return savedPos;
    }

    /**
     * Converts an OSM node to a POS domain object.
     * Extracts relevant tags from OSM data and maps them to POS fields.
     * Validates that all required fields are present.
     *
     * @param osmNode the OSM node to convert
     * @return a POS domain object
     * @throws OsmNodeMissingFieldsException if required fields are missing or invalid
     */
    private @NonNull Pos convertOsmNodeToPos(@NonNull OsmNode osmNode) {
        log.debug("Converting OSM node {} to POS", osmNode.nodeId());

        // Extract required tags
        String name = osmNode.getTag("name", null);
        String street = osmNode.getTag("addr:street", null);
        String houseNumber = osmNode.getTag("addr:housenumber", null);
        String postalCodeStr = osmNode.getTag("addr:postcode", null);
        String city = osmNode.getTag("addr:city", null);

        // Validate required fields
        if (name == null || name.isBlank()) {
            log.error("OSM node {} is missing required field: name", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (street == null || street.isBlank()) {
            log.error("OSM node {} is missing required field: addr:street", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (houseNumber == null || houseNumber.isBlank()) {
            log.error("OSM node {} is missing required field: addr:housenumber", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (postalCodeStr == null || postalCodeStr.isBlank()) {
            log.error("OSM node {} is missing required field: addr:postcode", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (city == null || city.isBlank()) {
            log.error("OSM node {} is missing required field: addr:city", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }

        // Parse postal code
        Integer postalCode;
        try {
            postalCode = Integer.parseInt(postalCodeStr.trim());
        } catch (NumberFormatException e) {
            log.error("OSM node {} has invalid postal code: {}", osmNode.nodeId(), postalCodeStr);
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }

        // Map amenity tag to PosType
        String amenity = osmNode.getTag("amenity", "").toLowerCase().trim();
        PosType type = switch (amenity) {
            case "cafe", "restaurant", "coffee_shop" -> PosType.CAFE;
            case "bakery", "bakehouse", "pastry_shop" -> PosType.BAKERY;
            case "vending_machine" -> PosType.VENDING_MACHINE;
            default -> {
                log.warn("Unknown amenity type '{}' for OSM node {}, defaulting to CAFE", amenity, osmNode.nodeId());
                yield PosType.CAFE;
            }
        };

        // Extract optional fields with fallbacks
        String description = osmNode.getTag("description", "");
        if (description.isBlank()) {
            description = osmNode.getTag("operator", "");
        }
        if (description.isBlank()) {
            description = osmNode.getTag("cuisine", "");
        }
        if (description.isBlank()) {
            // Use amenity type as fallback description
            description = switch (type) {
                case CAFE -> "Café";
                case BAKERY -> "Bakery";
                case VENDING_MACHINE -> "Vending Machine";
                case CAFETERIA -> "Cafeteria";
            };
        }

        // Determine campus based on location
        // TODO: This is a simplified implementation. In production, you might want to use
        // the lat/lon coordinates to determine the campus more accurately.
        CampusType campus = determineCampusFromAddress(city, street, osmNode.lat(), osmNode.lon());

        // Build and return POS
        Pos pos = Pos.builder()
                .name(name)
                .description(description)
                .type(type)
                .campus(campus)
                .street(street)
                .houseNumber(houseNumber)
                .postalCode(postalCode)
                .city(city)
                .build();

        log.debug("Successfully converted OSM node {} to POS '{}'", osmNode.nodeId(), pos.name());
        return pos;
    }

    /**
     * Determines the campus type based on address and coordinates.
     * This is a simplified implementation that defaults to ALTSTADT.
     *
     * @param city the city name
     * @param street the street name
     * @param lat the latitude (may be null)
     * @param lon the longitude (may be null)
     * @return the determined campus type
     */
    private @NonNull CampusType determineCampusFromAddress(@NonNull String city, @NonNull String street, Double lat, Double lon) {
        // Simplified heuristics - in production, you might use a more sophisticated approach
        // such as geofencing with lat/lon coordinates or a database lookup

        // Default to ALTSTADT for Heidelberg
        if (city.toLowerCase().contains("heidelberg")) {
            return CampusType.ALTSTADT;
        }

        // For other cities, default to ALTSTADT
        log.debug("Defaulting to ALTSTADT campus for city: {}", city);
        return CampusType.ALTSTADT;
    }

    /**
     * Performs the actual upsert operation with consistent error handling and logging.
     * Database constraint enforces name uniqueness - data layer will throw DuplicatePosNameException if violated.
     * JPA lifecycle callbacks (@PrePersist/@PreUpdate) set timestamps automatically.
     *
     * @param pos the POS to upsert
     * @return the persisted POS with updated ID and timestamps
     * @throws DuplicatePosNameException if a POS with the same name already exists
     */
    private @NonNull Pos performUpsert(@NonNull Pos pos) throws DuplicatePosNameException {
        try {
            Pos upsertedPos = posDataService.upsert(pos);
            log.info("Successfully upserted POS with ID: {}", upsertedPos.id());
            return upsertedPos;
        } catch (DuplicatePosNameException e) {
            log.error("Error upserting POS '{}': {}", pos.name(), e.getMessage());
            throw e;
        }
    }
}
