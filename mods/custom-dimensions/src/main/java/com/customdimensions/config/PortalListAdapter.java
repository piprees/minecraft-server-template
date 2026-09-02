package com.customdimensions.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The "portal" field accepts one object or an array of them; both land as a
 * list in config order. A single portal serialises back as an object, so a
 * Gson round-trip keeps the shape the file was written in.
 */
public class PortalListAdapter implements JsonDeserializer<List<DimensionConfig.Portal>>,
        JsonSerializer<List<DimensionConfig.Portal>> {

    @Override
    public List<DimensionConfig.Portal> deserialize(JsonElement json, Type type,
                                                    JsonDeserializationContext context) {
        List<DimensionConfig.Portal> portals = new ArrayList<>();
        if (json == null || json.isJsonNull()) {
            return portals;
        }
        if (json.isJsonObject()) {
            portals.add(context.deserialize(json, DimensionConfig.Portal.class));
            return portals;
        }
        if (!json.isJsonArray()) {
            throw new JsonParseException("portal must be an object or an array of objects");
        }
        for (JsonElement element : json.getAsJsonArray()) {
            if (element.isJsonNull()) {
                continue;
            }
            if (!element.isJsonObject()) {
                throw new JsonParseException("every entry in a portal array must be an object");
            }
            portals.add(context.deserialize(element, DimensionConfig.Portal.class));
        }
        return portals;
    }

    @Override
    public JsonElement serialize(List<DimensionConfig.Portal> portals, Type type,
                                 JsonSerializationContext context) {
        if (portals == null || portals.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        if (portals.size() == 1) {
            return context.serialize(portals.get(0), DimensionConfig.Portal.class);
        }
        JsonArray array = new JsonArray();
        for (DimensionConfig.Portal portal : portals) {
            array.add(context.serialize(portal, DimensionConfig.Portal.class));
        }
        return array;
    }
}
