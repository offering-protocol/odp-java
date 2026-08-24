package org.offeringprotocol.odp.service;

@FunctionalInterface
public interface CatalogHandler {
    Object handle(CatalogRequest request);
}
