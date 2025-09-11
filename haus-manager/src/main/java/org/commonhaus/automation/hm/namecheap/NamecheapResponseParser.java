package org.commonhaus.automation.hm.namecheap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.quarkus.logging.Log;

public class NamecheapResponseParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    public static DomainListResponse parseDomainListResponse(String xmlResponse) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlResponse.getBytes()));

            // Check if response is OK
            Element root = doc.getDocumentElement();
            String status = root.getAttribute("Status");
            if (!"OK".equals(status)) {
                Log.warn("Namecheap API returned non-OK status: " + status);
                return new DomainListResponse(List.of(), new PaginationInfo(0, 1, 10));
            }

            // Parse domains
            List<DomainRecord> domains = new ArrayList<>();
            NodeList domainElements = doc.getElementsByTagName("Domain");

            for (int i = 0; i < domainElements.getLength(); i++) {
                Element domainElement = (Element) domainElements.item(i);
                domains.add(parseDomainElement(domainElement));
            }

            // Parse pagination
            PaginationInfo pagination = parsePaginationInfo(doc);

            return new DomainListResponse(domains, pagination);

        } catch (Exception e) {
            Log.error("Failed to parse Namecheap domain list response", e);
            return new DomainListResponse(List.of(), new PaginationInfo(0, 1, 10));
        }
    }

    private static DomainRecord parseDomainElement(Element element) {
        return new DomainRecord(
                element.getAttribute("Name"),
                parseDate(element.getAttribute("Expires")),
                "true".equalsIgnoreCase(element.getAttribute("IsExpired")),
                "true".equalsIgnoreCase(element.getAttribute("IsLocked")),
                "true".equalsIgnoreCase(element.getAttribute("AutoRenew")),
                "true".equalsIgnoreCase(element.getAttribute("IsOurDNS")));
    }

    private static PaginationInfo parsePaginationInfo(Document doc) {
        try {
            Element pagingElement = (Element) doc.getElementsByTagName("Paging").item(0);
            if (pagingElement == null) {
                return new PaginationInfo(0, 1, 10);
            }

            int totalItems = Integer.parseInt(getElementText(pagingElement, "TotalItems"));
            int currentPage = Integer.parseInt(getElementText(pagingElement, "CurrentPage"));
            int pageSize = Integer.parseInt(getElementText(pagingElement, "PageSize"));

            return new PaginationInfo(totalItems, currentPage, pageSize);
        } catch (Exception e) {
            Log.warn("Failed to parse pagination info, using defaults", e);
            return new PaginationInfo(0, 1, 10);
        }
    }

    private static String getElementText(Element parent, String tagName) {
        NodeList nodeList = parent.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return "";
    }

    private static LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FORMAT);
        } catch (Exception e) {
            Log.warn("Failed to parse date: " + dateStr);
            return null;
        }
    }
}
