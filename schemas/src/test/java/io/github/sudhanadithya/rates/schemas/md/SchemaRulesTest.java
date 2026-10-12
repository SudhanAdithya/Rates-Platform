package io.github.sudhanadithya.rates.schemas.md;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Checks the rules in the schema's header comment against the XML itself, so they can't drift. */
class SchemaRulesTest {

    private static final Set<String> FLOATING_POINT = Set.of("float", "double");

    private static Document schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream in = SchemaRulesTest.class.getResourceAsStream("/sbe/rates-md.xml")) {
            assertThat(in).as("schema on the classpath").isNotNull();
            schema = factory.newDocumentBuilder().parse(in);
        }
    }

    @Test
    @DisplayName("FR.MD.06 no float or double types or fields in the SBE schema")
    void noFloatingPointAnywhere() {
        final List<String> offenders = new ArrayList<>();
        final NodeList all = schema.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            final Element element = (Element) all.item(i);
            if (FLOATING_POINT.contains(element.getAttribute("primitiveType"))
                || FLOATING_POINT.contains(element.getAttribute("type"))) {
                offenders.add(element.getTagName() + " " + element.getAttribute("name"));
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("FR.MD.06 every price field is an int64 count of ticks")
    void priceFieldsAreInt64Ticks() {
        final NodeList fields = schema.getElementsByTagName("field");
        int priceFields = 0;
        for (int i = 0; i < fields.getLength(); i++) {
            final Element field = (Element) fields.item(i);
            if (field.getAttribute("name").toLowerCase().contains("price")) {
                priceFields++;
                assertThat(field.getAttribute("name")).endsWith("Ticks");
                assertThat(field.getAttribute("type")).isEqualTo("int64");
            }
        }
        assertThat(priceFields).isPositive();
    }
}
