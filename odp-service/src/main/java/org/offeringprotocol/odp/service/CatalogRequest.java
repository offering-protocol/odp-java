package org.offeringprotocol.odp.service;

public record CatalogRequest(
        String identifier,
        String representation,
        Integer limit,
        String cursor,
        String language,
        String body,
        OdpHttpRequest request) {}
