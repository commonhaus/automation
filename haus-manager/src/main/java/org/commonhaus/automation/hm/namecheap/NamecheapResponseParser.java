package org.commonhaus.automation.hm.namecheap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainListResponse;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.quarkus.logging.Log;

public class NamecheapResponseParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * Validate the Namecheap API response and throw NamecheapException if there are errors.
     *
     * @param xmlResponse XML response from Namecheap API
     * @throws NamecheapException if the API returns Status="ERROR"
     */
    public static void validateResponse(String xmlResponse) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlResponse.getBytes()));

            Element root = doc.getDocumentElement();
            String status = root.getAttribute("Status");

            if ("ERROR".equals(status)) {
                List<NamecheapException.NamecheapError> errors = new ArrayList<>();
                NodeList errorElements = doc.getElementsByTagName("Error");

                for (int i = 0; i < errorElements.getLength(); i++) {
                    Element errorElement = (Element) errorElements.item(i);
                    String errorNumber = errorElement.getAttribute("Number");
                    String errorMessage = errorElement.getTextContent();
                    errors.add(new NamecheapException.NamecheapError(errorNumber, errorMessage));
                }

                if (errors.isEmpty()) {
                    throw new NamecheapException("Namecheap API returned ERROR status with no error details");
                }

                throw new NamecheapException(errors);
            }

            if (!"OK".equals(status)) {
                throw new NamecheapException("Namecheap API returned unexpected status: " + status);
            }
        } catch (NamecheapException e) {
            throw e;
        } catch (Exception e) {
            throw new NamecheapException("Failed to parse Namecheap API response", e);
        }
    }

    public static DomainListResponse parseDomainListResponse(String xmlResponse) {
        // Validate response and throw exception if there are errors
        validateResponse(xmlResponse);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlResponse.getBytes()));

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

        } catch (NamecheapException e) {
            throw e;
        } catch (Exception e) {
            throw new NamecheapException("Failed to parse Namecheap domain list response", e);
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

    public static boolean parseSetContactsResponse(String xmlResponse) {
        // Validate response and throw exception if there are errors
        validateResponse(xmlResponse);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlResponse.getBytes()));

            // Find DomainSetContactResult element
            NodeList resultElements = doc.getElementsByTagName("DomainSetContactResult");
            if (resultElements.getLength() == 0) {
                throw new NamecheapException("No DomainSetContactResult element found in response");
            }

            Element resultElement = (Element) resultElements.item(0);
            String isSuccess = resultElement.getAttribute("IsSuccess");

            return "true".equalsIgnoreCase(isSuccess);
        } catch (NamecheapException e) {
            throw e;
        } catch (Exception e) {
            throw new NamecheapException("Failed to parse Namecheap set contacts response", e);
        }
    }

    /**
     * Parse contacts response from namecheap.domains.getContacts API call.
     *
     * @param xmlResponse XML response
     * @return DomainContacts with all 4 contact types
     * @throws NamecheapException if the response has errors or is invalid
     */
    public static DomainContacts parseGetContactsResponse(String xmlResponse) {
        // Validate response and throw exception if there are errors
        validateResponse(xmlResponse);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlResponse.getBytes()));

            // Find DomainContactsResult element
            NodeList resultElements = doc.getElementsByTagName("DomainContactsResult");
            if (resultElements.getLength() == 0) {
                throw new NamecheapException("No DomainContactsResult element found in response");
            }

            Element resultElement = (Element) resultElements.item(0);

            // Parse each contact type (use actual contacts, not WhoisGuard)
            ContactInfo registrant = parseContactElement(resultElement, "Registrant");
            ContactInfo tech = parseContactElement(resultElement, "Tech");
            ContactInfo admin = parseContactElement(resultElement, "Admin");
            ContactInfo auxBilling = parseContactElement(resultElement, "AuxBilling");

            // Registrant is required; for others, fall back to registrant if missing
            if (registrant == null) {
                throw new NamecheapException("Missing required Registrant contact in response");
            }

            return new DomainContacts(registrant,
                    tech == null ? registrant : tech,
                    admin == null ? registrant : admin,
                    auxBilling == null ? registrant : auxBilling,
                    tech != null,
                    admin != null,
                    auxBilling != null);

        } catch (NamecheapException e) {
            throw e;
        } catch (Exception e) {
            throw new NamecheapException("Failed to parse Namecheap get contacts response", e);
        }
    }

    private static ContactInfo parseContactElement(Element parent, String contactType) {
        NodeList contactElements = parent.getElementsByTagName(contactType);
        if (contactElements.getLength() == 0) {
            Log.warn("No " + contactType + " element found");
            return null;
        }

        Element contactElement = (Element) contactElements.item(0);
        boolean readOnly = "true".equalsIgnoreCase(contactElement.getAttribute("ReadOnly"));

        // Parse required fields
        String firstName = getElementText(contactElement, "FirstName");
        String lastName = getElementText(contactElement, "LastName");
        String address1 = getElementText(contactElement, "Address1");
        String city = getElementText(contactElement, "City");
        String stateProvince = getElementText(contactElement, "StateProvince");
        String postalCode = getElementText(contactElement, "PostalCode");
        String country = getElementText(contactElement, "Country");
        String phone = getElementText(contactElement, "Phone");
        String emailAddress = getElementText(contactElement, "EmailAddress");

        // Parse optional fields
        String orgName = getElementText(contactElement, "OrganizationName");
        String jobTitle = getElementText(contactElement, "JobTitle");
        String address2 = getElementText(contactElement, "Address2");
        String phoneExt = getElementText(contactElement, "PhoneExt");
        String fax = getElementText(contactElement, "Fax");

        return new ContactInfo(
                firstName,
                lastName,
                address1,
                city,
                stateProvince,
                postalCode,
                country,
                phone,
                emailAddress,
                Optional.ofNullable(emptyToNull(orgName)),
                Optional.ofNullable(emptyToNull(jobTitle)),
                Optional.ofNullable(emptyToNull(address2)),
                Optional.ofNullable(emptyToNull(phoneExt)),
                Optional.ofNullable(emptyToNull(fax)),
                readOnly);
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
