package org.offeringprotocol.odp.core;

public record ResourceImage(String alt, Integer height, String src, String type, Integer width) {
    public ResourceImage {
        if (src == null || src.isBlank()) {
            throw new IllegalArgumentException("Image source is required");
        }
    }
}
