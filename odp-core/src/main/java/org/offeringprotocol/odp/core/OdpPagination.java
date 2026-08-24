package org.offeringprotocol.odp.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded helpers for following opaque ODP continuation references. */
public final class OdpPagination {
    public static final int MAXIMUM_PAGES = 16;

    private OdpPagination() {}

    public static <T> List<Page<T>> pages(Page<T> first, PageLoader<T> loader) {
        List<Page<T>> pages = new ArrayList<>();
        Set<String> continuations = new HashSet<>();
        Page<T> page = first;
        for (int count = 0; count < MAXIMUM_PAGES; count++) {
            pages.add(page);
            if (page.next() == null) {
                return List.copyOf(pages);
            }
            if (!continuations.add(page.next())) {
                throw new IllegalStateException("ODP pagination loop detected");
            }
            if (count == MAXIMUM_PAGES - 1) {
                throw new IllegalStateException("ODP pagination exceeded the 16-page traversal limit");
            }
            page = loader.load(page.next());
        }
        throw new IllegalStateException("ODP pagination exceeded the 16-page traversal limit");
    }

    public static <T> List<T> items(Page<T> first, PageLoader<T> loader) {
        return pages(first, loader).stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    @FunctionalInterface
    public interface PageLoader<T> {
        Page<T> load(String continuation);
    }
}
