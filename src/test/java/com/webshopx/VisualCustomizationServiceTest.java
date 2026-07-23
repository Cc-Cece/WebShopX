package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class VisualCustomizationServiceTest {
    @Test
    void defaultsContainCompletePriorityChainsWithFixedFallbacks() {
        VisualCustomizationService.VisualSettings settings =
                VisualCustomizationService.VisualSettings.defaults();

        assertEquals(
                List.of(
                        "ADMIN_MATERIAL",
                        "OFFICIAL_PRODUCT",
                        "MARKET_LISTING",
                        "VISUAL_PACK",
                        "BUILTIN",
                        "FALLBACK"),
                settings.iconPriority());
        assertEquals(
                List.of(
                        "ITEM_CUSTOM_NAME",
                        "ADMIN_MATERIAL",
                        "OFFICIAL_PRODUCT",
                        "MARKET_LISTING",
                        "VISUAL_PACK",
                        "BUILTIN_LOCALE",
                        "EN_US",
                        "FORMATTED_ID"),
                settings.namePriority());
    }
}
