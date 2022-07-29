package eu.einfracentral.registry.service;


import eu.einfracentral.domain.*;
import eu.openminted.registry.core.domain.Browsing;
import eu.openminted.registry.core.domain.FacetFilter;
import eu.openminted.registry.core.domain.Paging;
import eu.openminted.registry.core.domain.Resource;
import eu.openminted.registry.core.exception.ResourceNotFoundException;
import eu.openminted.registry.core.service.ResourceCRUDService;
import eu.openminted.registry.core.service.SearchService;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

public interface ResourceBundleService<T> extends ResourceCRUDService<T, Authentication> {

    /**
     * Method to add a new resource.
     *
     * @param resource
     * @param auth
     * @return
     */
    T addResource(T resource, Authentication auth);

    /**
     * Method to add a new resource from external catalogue.
     *
     * @param resource
     * @param catalogueId
     * @param auth
     * @return
     */
    T addResource(T resource, String catalogueId, Authentication auth);

    /**
     * Method to update a resource.
     *
     * @param resource
     * @param comment
     * @param auth
     * @return
     * @throws ResourceNotFoundException
     */
    T updateResource(T resource, String comment, Authentication auth) throws ResourceNotFoundException;

    /**
     * Method to update a resource.
     *
     * @param resource
     * @param catalogueId
     * @param comment
     * @param auth
     * @return
     * @throws ResourceNotFoundException
     */
    T updateResource(T resource, String catalogueId, String comment, Authentication auth) throws ResourceNotFoundException;

    T getCatalogueService(String catalogueId, String serviceId, Authentication auth);

    /**
     * Returns the Service with the specified id.
     *
     * @param id          of the Service.
     * @param catalogueId
     * @return service.
     */
    T get(String id, String catalogueId);

    /**
     * Get InfraServices by a specific field.
     *
     * @param field
     * @param auth
     * @return
     */
    Map<String, List<T>> getBy(String field, Authentication auth) throws NoSuchFieldException;

    /**
     * Get RichServices with the specified ids.
     *
     * @param ids
     * @return
     */
    List<RichService> getByIds(Authentication authentication, String... ids);

    /**
     * Gets all Services with extra fields like views and ratings
     *
     * @param ff
     * @return
     */
    Paging<RichService> getRichServices(FacetFilter ff, Authentication auth);

    /**
     * Gets the specific Service with extra fields like views and ratings
     *
     * @param id
     * @param catalogueId
     * @param auth
     * @return
     */
    RichService getRichService(String id, String catalogueId, Authentication auth);

    /**
     * Creates a RichService for the specific Service
     *
     * @return
     */
    RichService createRichService(T serviceBundle, Authentication auth);

    /**
     * Creates RichServices for a list of given Services
     *
     * @return
     */
    List<RichService> createRichServices(List<T> serviceBundleList, Authentication auth);


    /**
     * Check if the Service exists.
     *
     * @param ids
     * @return
     */
    boolean exists(SearchService.KeyValue... ids);

    /**
     * Get the service resource.
     *
     * @param id
     * @param catalogueId
     * @return Resource
     */
    Resource getResource(String id, String catalogueId);

    /**
     * Get the History of the ResourceBundle with the specified id.
     *
     * @param id
     * @param catalogueId
     * @return
     */
    @Deprecated
    Paging<ResourceHistory> getHistory(String id, String catalogueId);

    /**
     * Get the History of a specific resource version of the ResourceBundle with the specified id.
     *
     * @param resourceId
     * @param catalogueId
     * @param versionId
     * @return
     */
    @Deprecated
    Service getVersionHistory(String resourceId, String catalogueId, String versionId);

    /**
     * Get inactive Services.
     *
     * @return
     */
    Paging<T> getInactiveServices();

    /**
     * Validates the given service.
     *
     * @param service
     * @return
     */
    boolean validate(T service);

    /**
     * Create a list of random services.
     *
     * @return
     */
    List<Service> createFeaturedServices();

    /**
     * Sets a Service as active/inactive.
     *
     * @param serviceId
     * @param version
     * @param active
     * @param auth
     * @return
     */
    T publish(String serviceId, String version, boolean active, Authentication auth);

    /**
     * Return children vocabularies from parent vocabularies
     *
     * @param type
     * @param parent
     * @param rec
     * @return
     */
    List<String> getChildrenFromParent(String type, String parent, List<Map<String, Object>> rec);

    /**
     * Gets all Services for Admins Page
     *
     * @param filter
     * @param auth
     */
    Browsing<T> getAllForAdmin(FacetFilter filter, Authentication auth);

    /**
     * @param serviceId
     * @param actionType
     * @param auth
     * @return
     */
    T auditResource(String serviceId, String comment, LoggingInfo.ActionType actionType, Authentication auth);

    /**
     * @param ff
     * @param auth
     * @param auditingInterval
     * @return
     */
    Paging<T> getRandomResources(FacetFilter ff, String auditingInterval, Authentication auth);

    /**
     * @param providerId
     * @param auth
     * @return
     */
    List<T> getInfraServices(String providerId, Authentication auth);

    /**
     * @param providerId
     * @param catalogueId
     * @param auth
     * @return
     */
    Paging<T> getInfraServices(String catalogueId, String providerId, Authentication auth);

    List<Service> getServices(String providerId, Authentication auth);

    List<Service> getActiveServices(String providerId);

    T getServiceTemplate(String providerId, Authentication auth);

    Service getFeaturedService(String providerId);

    List<T> getInactiveServices(String providerId);

    /**
     * @param resourceId
     * @param auth
     */
    void sendEmailNotificationsToProvidersWithOutdatedResources(String resourceId, Authentication auth);

    /**
     * Get the History of the Resource with the specified id.
     *
     * @param id
     * @param catalogueId
     * @return
     */
    Paging<LoggingInfo> getLoggingInfoHistory(String id, String catalogueId);

    /**
     * @param id
     * @param status
     * @param active
     * @param auth
     * @return
     */
    T verifyResource(String id, String status, Boolean active, Authentication auth);

    /**
     * @param resourceId
     * @param newProvider
     * @param comment
     * @param auth
     */
    T changeProvider(String resourceId, String newProvider, String comment, Authentication auth);

}
