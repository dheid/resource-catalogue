package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.*;
import eu.einfracentral.exception.ResourceException;
import eu.einfracentral.exception.ResourceNotFoundException;
import eu.einfracentral.exception.ValidationException;
import eu.einfracentral.registry.service.*;
import eu.einfracentral.service.*;
import eu.einfracentral.service.search.SearchServiceEIC;
import eu.einfracentral.utils.FacetFilterUtils;
import eu.einfracentral.utils.FacetLabelService;
import eu.einfracentral.utils.ProviderResourcesCommonMethods;
import eu.einfracentral.utils.TextUtils;
import eu.einfracentral.validators.FieldValidator;
import eu.openminted.registry.core.domain.*;
import eu.openminted.registry.core.domain.index.IndexField;
import eu.openminted.registry.core.service.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.Validator;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static eu.einfracentral.config.CacheConfig.*;
import static java.util.stream.Collectors.toList;

public abstract class AbstractServiceBundleManager<T extends ServiceBundle> extends AbstractGenericService<T> implements ServiceBundleService<T> {

    private static final Logger logger = LogManager.getLogger(AbstractServiceBundleManager.class);

    public AbstractServiceBundleManager(Class<T> typeParameterClass) {
        super(typeParameterClass);
    }

    @Autowired
    private VocabularyService vocabularyService;
    @Autowired
    private ProviderService<ProviderBundle, Authentication> providerService;
    @Autowired
    private FacetLabelService facetLabelService;
    @Autowired
    @Qualifier("serviceSync")
    private SynchronizerService<eu.einfracentral.domain.Service> synchronizerService;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private SearchServiceEIC searchServiceEIC;
    @Autowired
    private IdCreator idCreator;
    private List<String> browseBy;
    private Map<String, String> labels;
    @Autowired
    private SecurityService securityService;
    @Autowired
    private FieldValidator fieldValidator;
    @Value("${project.catalogue.name}")
    private String catalogueName;

    @Autowired
    @Qualifier("serviceValidator")
    private Validator serviceValidator;
    @Autowired
    private GenericResourceService genericResourceService;
    @Autowired
    private ProviderResourcesCommonMethods commonMethods;

    @PostConstruct
    void initLabels() {
        resourceType = resourceTypeService.getResourceType(getResourceType());
        Set<String> browseSet = new HashSet<>();
        Map<String, Set<String>> sets = new HashMap<>();
        labels = new HashMap<>();
        labels.put("resourceType", "Resource Type");
        for (IndexField f : resourceTypeService.getResourceTypeIndexFields(getResourceType())) {
            sets.putIfAbsent(f.getResourceType().getName(), new HashSet<>());
            labels.put(f.getName(), f.getLabel());
            if (f.getLabel() != null) {
                sets.get(f.getResourceType().getName()).add(f.getName());
            }
        }
        boolean flag = true;
        for (Map.Entry<String, Set<String>> entry : sets.entrySet()) {
            if (flag) {
                browseSet.addAll(entry.getValue());
                flag = false;
            } else {
                browseSet.retainAll(entry.getValue());
            }
        }
        browseBy = new ArrayList<>();
        browseBy.addAll(browseSet);
        browseBy.add("resourceType");
        java.util.Collections.sort(browseBy);
        logger.info("Generated generic service for '{}'[{}]", getResourceType(), getClass().getSimpleName());
    }

    @Override
    public String getResourceType() {
        return resourceType.getName();
    }

    //    @Override
    public T get(String id, String catalogueId) {
        Resource resource = getResource(id, catalogueId);
        if (resource == null) {
            throw new ResourceNotFoundException(String.format("Could not find Resource with id: %s and catalogueId: %s", id, catalogueId));
        }
        return deserialize(resource);
    }

    // TODO: REMOVE ME
    private T checkIdExistanceInOtherCatalogues(String id) {
        FacetFilter ff = new FacetFilter();
        ff.setQuantity(maxQuantity);
        ff.addFilter("resource_internal_id", id);
        List<T> allResources = getAll(ff, null).getResults();
        if (allResources.size() > 0) {
            return allResources.get(0);
        }
        return null;
    }

    // TODO: REMOVE ME
    //    @Override
    public T get(String id) {
        T resource = null;
        try {
            resource = get(id, catalogueName);
        } catch (ResourceNotFoundException e) {
            resource = checkIdExistanceInOtherCatalogues(id);
            if (resource == null) {
                throw e;
            }
        }
        return resource;
    }

    @Override
    public Browsing<T> getAll(FacetFilter filter, Authentication auth) {
        // if user is Unauthorized, return active/latest ONLY
        updateFacetFilterConsideringTheAuthorization(filter, auth);

        filter.setBrowseBy(browseBy);
        filter.setResourceType(getResourceType());

        return getMatchingResources(filter);
    }

    @Override
    public Browsing<T> getMy(FacetFilter filter, Authentication auth) {
        throw new UnsupportedOperationException("Not yet Implemented");
    }

    @Override
    @CacheEvict(cacheNames = {CACHE_VISITS, CACHE_PROVIDERS, CACHE_FEATURED}, allEntries = true)
    public T add(T serviceBundle, Authentication auth) {
        logger.trace("User '{}' is attempting to add a new Resource: {}", auth, serviceBundle);
        if (serviceBundle.getService().getId() == null) {
            serviceBundle.getService().setId(idCreator.createServiceId(serviceBundle));
        }
        // if Resource version is empty set it null
        if ("".equals(serviceBundle.getService().getVersion())) {
            serviceBundle.getService().setVersion(null);
        }
        if (exists(serviceBundle)) {
            throw new ResourceException("Resource already exists!", HttpStatus.CONFLICT);
        }

        prettifyServiceTextFields(serviceBundle, ",");

        String serialized;
        serialized = parserPool.serialize(serviceBundle, ParserService.ParserServiceTypes.XML);
        Resource created = new Resource();
        created.setPayload(serialized);
        created.setResourceType(resourceType);

        resourceService.addResource(created);
        synchronizerService.syncAdd(serviceBundle.getPayload());

        return serviceBundle;
    }

    @Override
    @CacheEvict(cacheNames = {CACHE_VISITS, CACHE_PROVIDERS, CACHE_FEATURED}, allEntries = true)
    public T update(T serviceBundle, Authentication auth) {
        logger.trace("User '{}' is attempting to update the Resource: {}", auth, serviceBundle);
        // if Resource version is empty set it null
        if ("".equals(serviceBundle.getService().getVersion())) {
            serviceBundle.getService().setVersion(null);
        }
        Resource existing = getResource(serviceBundle.getService().getId(), serviceBundle.getService().getCatalogueId());
        if (existing == null) {
            throw new ResourceNotFoundException(
                    String.format("Could not update Resource with id '%s' because it does not exist",
                            serviceBundle.getService().getId()));
        }

        prettifyServiceTextFields(serviceBundle, ",");
        existing.setPayload(serialize(serviceBundle));
        existing.setResourceType(resourceType);

        resourceService.updateResource(existing);
        synchronizerService.syncUpdate(serviceBundle.getPayload());

        return serviceBundle;
    }

    @Override
    @CacheEvict(cacheNames = {CACHE_VISITS, CACHE_PROVIDERS, CACHE_FEATURED}, allEntries = true)
    public void delete(T serviceBundle) {
        logger.trace("User is attempting to delete the Resource: {}", serviceBundle);
        if (serviceBundle == null || serviceBundle.getService().getId() == null) {
            throw new ServiceException("You cannot delete a null Resource or Resource with null id field");
        }
        resourceService.deleteResource(getResource(serviceBundle.getService().getId(), serviceBundle.getService().getCatalogueId()).getId());
        synchronizerService.syncDelete(serviceBundle.getPayload());
    }

    @Override
    public boolean validate(ServiceBundle serviceBundle) {
        Service service = serviceBundle.getService();
        //If we want to reject bad vocab ids instead of silently accept, here's where we do it
        logger.debug("Validating Resource with id: {}", service.getId());

        try {
            fieldValidator.validate(serviceBundle);
        } catch (IllegalAccessException e) {
            logger.error("", e);
        }
        serviceValidator.validate(serviceBundle, null);

        return true;
    }

    @Override
    public Map<String, List<T>> getBy(String field, Authentication auth) throws NoSuchFieldException {
        Field serviceField = null;
        try {
            serviceField = Service.class.getDeclaredField(field);
        } catch (NoSuchFieldException e) {
            logger.warn("Attempt to find field '{}' in Service failed. Trying in ServiceBundle...", field);
            serviceField = ServiceBundle.class.getDeclaredField(field);
        }
        serviceField.setAccessible(true);

        FacetFilter ff = new FacetFilter();
        ff.setQuantity(maxQuantity);
        ff.addFilter("published", false);
        Browsing<T> services = getAll(ff, auth);

        final Field f = serviceField;
        final String undef = "undefined";
        return services.getResults().stream().collect(Collectors.groupingBy(service -> {
            try {
                return f.get(service.getPayload()) != null ? f.get(service.getPayload()).toString() : undef;
            } catch (IllegalAccessException | IllegalArgumentException e) {
                logger.warn("Warning", e);
                try {
                    return f.get(service) != null ? f.get(service).toString() : undef;
                } catch (IllegalAccessException e1) {
                    logger.error("ERROR", e1);
                }
                return undef;
            }
        }, Collectors.mapping((T service) -> service, toList())));
    }

    @Override
    public List<ServiceBundle> getByIds(Authentication auth, String... ids) {
        List<ServiceBundle> resources;
        resources = Arrays.stream(ids)
                .map(id ->
                {
                    try {
                        return get(id, catalogueName);
                    } catch (ServiceException | ResourceNotFoundException e) {
                        return null;
                    }

                })
                .filter(Objects::nonNull)
                .collect(toList());
        return resources;
    }

    @Override
    public boolean exists(SearchService.KeyValue... ids) {
        Resource resource;
        try {
            resource = this.searchService.searchId(getResourceType(), ids);
            return resource != null;
        } catch (UnknownHostException e) {
            logger.error(e);
            throw new ServiceException(e);
        }
    }

    String serialize(T serviceBundle) {
        String serialized;
        serialized = parserPool.serialize(serviceBundle, ParserService.ParserServiceTypes.XML);
        return serialized;
    }

    public T deserialize(Resource resource) {
        if (resource == null) {
            logger.warn("attempt to deserialize null resource");
            return null;
        }
        return parserPool.deserialize(resource, typeParameterClass);
    }

    private boolean exists(T serviceBundle) {
        return getResource(serviceBundle.getService().getId(), serviceBundle.getService().getCatalogueId()) != null;
    }

    public Resource getResourceById(String resourceId) {
        List<Resource> resource = searchService.cqlQuery(String.format("id = \"%s\"", resourceId), resourceType.getName(),
                1, 0, "modifiedAt", "DESC").getResults();
        if (resource.isEmpty()) {
            return null;
        }
        return resource.get(0);
    }

    public Resource getResource(String id, String catalogueId) {
        Paging<Resource> resources;
        resources = searchService
                .cqlQuery(String.format("resource_internal_id = \"%s\"  AND catalogue_id = \"%s\"", id, catalogueId),
                        resourceType.getName(), maxQuantity, 0, "modifiedAt", "DESC");
        if (resources.getTotal() > 0) {
            return resources.getResults().get(0);
        }
        return null;
    }

    @Deprecated
    public List<Resource> getResources(String id, String catalogueId) {
        Paging<Resource> resources;
        resources = searchService
                .cqlQuery(String.format("resource_internal_id = \"%s\"  AND catalogue_id = \"%s\"", id, catalogueId),
                        resourceType.getName(), maxQuantity, 0, "modifiedAt", "DESC");
        if (resources != null) {
            return resources.getResults();
        }
        return Collections.emptyList();
    }

    /**
     * Adds spaces after ',' if they don't already exist and removes spaces before
     *
     * @param serviceBundle
     * @param specialCharacters
     * @return
     */
    protected T prettifyServiceTextFields(T serviceBundle, String specialCharacters) {
        serviceBundle.getService().setTagline(TextUtils.prettifyText(serviceBundle.getService().getTagline(), specialCharacters));
        return serviceBundle;
    }

    private Browsing<T> getMatchingResources(FacetFilter ff) {
        Browsing<T> resources;

        resources = genericResourceService.getResults(ff);
        if (!resources.getResults().isEmpty() && !resources.getFacets().isEmpty()) {
            resources.setFacets(facetLabelService.createLabels(resources.getFacets()));
        }

        return resources;
    }

    @Override
    public Bundle<?> getResourceTemplate(String providerId, Authentication auth) {
        FacetFilter ff = new FacetFilter();
        ff.addFilter("resource_organisation", providerId);
        ff.addFilter("catalogue_id", catalogueName);
        List<T> allProviderResources = getAll(ff, auth).getResults();
        for (T resourceBundle : allProviderResources) {
            if (resourceBundle.getStatus().equals(vocabularyService.get("pending resource").getId())) {
                return resourceBundle;
            }
        }
        return null;
    }

    @Override
    protected Browsing<T> getResults(FacetFilter filter) {
        Browsing<T> browsing;
        filter.setResourceType(getResourceType());
        browsing = convertToBrowsingEIC(searchServiceEIC.search(filter));

        browsing.setFacets(createCorrectFacets(browsing.getFacets(), filter));
        return browsing;
    }

    public List<Facet> createCorrectFacets(List<Facet> serviceFacets, FacetFilter ff) {
        ff.setQuantity(0);

        Map<String, List<Object>> allFilters = FacetFilterUtils.getFacetFilterFilters(ff);

        List<String> reverseOrderedKeys = new LinkedList<>(allFilters.keySet());
        Collections.reverse(reverseOrderedKeys);

        for (String filterKey : reverseOrderedKeys) {
            Map<String, List<Object>> someFilters = new LinkedHashMap<>(allFilters);

            // if last filter is "latest" or "active" continue to next iteration
            if ("active".equals(filterKey)) {
                continue;
            }
            someFilters.remove(filterKey);

            FacetFilter facetFilter = FacetFilterUtils.createMultiFacetFilter(someFilters);
            facetFilter.setResourceType(getResourceType());
            facetFilter.setBrowseBy(Collections.singletonList(filterKey));
            List<Facet> facetsCategory = convertToBrowsingEIC(searchServiceEIC.search(facetFilter)).getFacets();

            for (Facet facet : serviceFacets) {
                if (facet.getField().equals(filterKey)) {
                    for (Facet facetCategory : facetsCategory) {
                        if (facetCategory.getField().equals(facet.getField())) {
                            serviceFacets.set(serviceFacets.indexOf(facet), facetCategory);
                            break;
                        }
                    }
                    break;
                }
            }
            break;
        }

        return removeEmptyFacets(serviceFacets);
    }

    private List<Facet> removeEmptyFacets(List<Facet> facetList) {
        return facetList.stream().filter(facet -> !facet.getValues().isEmpty()).collect(toList());
    }

    private Browsing<T> convertToBrowsingEIC(@NotNull Paging<Resource> paging) {
        List<T> results = paging.getResults()
                .stream()
                .map(res -> parserPool.deserialize(res, typeParameterClass))
                .collect(Collectors.toList());
        return new Browsing<>(paging, results, labels);
    }

    public List<String> getChildrenFromParent(String type, String parent, List<Map<String, Object>> rec) {
        //TODO: Refactor to a more proper way (sql JOIN OR elastic)
        List<String> finalResults = new ArrayList<>();
        List<String> allSub = new ArrayList<>();
        List<String> correctedSubs = new ArrayList<>();
        for (Map<String, Object> map : rec) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String trimmed = entry.getValue().toString().replace("{", "").replace("}", "");
                if (!allSub.contains(trimmed)) {
                    allSub.add((trimmed));
                }
            }
        }
        // Required step to fix joint subcategories (sub1,sub2,sub3) who passed as 1 value
        for (String item : allSub) {
            if (item.contains(",")) {
                String[] itemParts = item.split(",");
                correctedSubs.addAll(Arrays.asList(itemParts));
            } else {
                correctedSubs.add(item);
            }
        }
        if (type.equalsIgnoreCase("SUPERCATEGORY") || type.equalsIgnoreCase("SCIENTIFIC_DOMAIN")) {
            String[] parts = parent.split("-"); //supercategory-natural_sciences
            for (String id : correctedSubs) {
                if (id.contains(parts[1])) {
                    finalResults.add(id);
                }
            }
        } else {
            String[] parts = parent.split("-"); //category-natural_sciences-math
            for (String id : correctedSubs) {
                if (id.contains(parts[2])) {
                    finalResults.add(id);
                }
            }
        }
        return finalResults;
    }

    public Browsing<T> getAllForAdmin(FacetFilter filter, Authentication auth) {
        filter.setBrowseBy(browseBy);
        filter.setResourceType(getResourceType());
        return getMatchingResources(filter);
    }

    // FIXME: not working...
    @Override
    public Paging<T> getRandomResources(FacetFilter ff, String auditingInterval, Authentication auth) {
        FacetFilter facetFilter = new FacetFilter();
        facetFilter.setQuantity(maxQuantity);
        facetFilter.addFilter("status", "approved resource");
        facetFilter.addFilter("published", "false");
        Browsing<T> serviceBrowsing = getAll(facetFilter, auth);
        List<T> servicesToBeAudited = new ArrayList<>();
        long todayEpochTime = System.currentTimeMillis();
        long interval = Instant.ofEpochMilli(todayEpochTime).atZone(ZoneId.systemDefault()).minusMonths(Integer.parseInt(auditingInterval)).toEpochSecond();
        for (T serviceBundle : serviceBrowsing.getResults()) {
            if (serviceBundle.getLatestAuditInfo() != null) {
                if (Long.parseLong(serviceBundle.getLatestAuditInfo().getDate()) > interval) {
                    servicesToBeAudited.add(serviceBundle);
                }
            }
        }
        Collections.shuffle(servicesToBeAudited);
        for (int i = servicesToBeAudited.size() - 1; i > ff.getQuantity() - 1; i--) {
            servicesToBeAudited.remove(i);
        }
        return new Browsing<>(servicesToBeAudited.size(), 0, servicesToBeAudited.size(), servicesToBeAudited, serviceBrowsing.getFacets());
    }

    @Override
    public ServiceBundle updateEOSCIFGuidelines(String resourceId, String catalogueId, List<EOSCIFGuidelines> eoscIFGuidelines, Authentication auth) {
        T bundle = get(resourceId, catalogueId);
        blockUpdateIfResourceIsPublished(bundle);
        ResourceExtras resourceExtras = bundle.getResourceExtras();
        if (resourceExtras == null) {
            ResourceExtras newResourceExtras = new ResourceExtras();
            List<EOSCIFGuidelines> newEOSCIFGuidelines = new ArrayList<>(eoscIFGuidelines);
            newResourceExtras.setEoscIFGuidelines(newEOSCIFGuidelines);
            bundle.setResourceExtras(newResourceExtras);
        } else {
            bundle.getResourceExtras().setEoscIFGuidelines(eoscIFGuidelines);
        }
        // check PID consistency
        checkEOSCIFGuidelinesPIDConsistency(bundle);

        createLoggingInfoEntriesForResourceExtraUpdates(bundle, auth);
        validate(bundle);
        update(bundle, auth);
        logger.info("User '{}'-'{}' updated field eoscIFGuidelines of the Resource '{}'",
                User.of(auth).getFullName(), User.of(auth).getEmail(), resourceId);
        return bundle;
    }

    private void blockUpdateIfResourceIsPublished(ServiceBundle serviceBundle) { //FIXME: DOES NOT WORK AS INTENDED
        if (serviceBundle.getMetadata().isPublished()) {
            throw new AccessDeniedException("You cannot directly update a Public Resource.");
        }
    }

    private void checkEOSCIFGuidelinesPIDConsistency(ServiceBundle serviceBundle) {
        List<String> pidList = new ArrayList<>();
        for (EOSCIFGuidelines eoscIFGuideline : serviceBundle.getResourceExtras().getEoscIFGuidelines()) {
            pidList.add(eoscIFGuideline.getPid());
        }
        Set<String> pidSet = new HashSet<>(pidList);
        if (pidSet.size() < pidList.size()) {
            throw new ValidationException("EOSCIFGuidelines cannot have duplicate PIDs.");
        }
    }

    public T getOrElseReturnNull(String id) {
        T serviceBundle;
        try {
            serviceBundle = get(id);
        } catch (ResourceNotFoundException e) {
            return null;
        }
        return serviceBundle;
    }

    public T getOrElseReturnNull(String id, String catalogueId) {
        T serviceBundle;
        try {
            serviceBundle = get(id, catalogueId);
        } catch (ResourceNotFoundException e) {
            return null;
        }
        return serviceBundle;
    }

    @Override
    public List<T> getInactiveResources(String providerId) {
        FacetFilter ff = new FacetFilter();
        ff.addFilter("resource_organisation", providerId);
        ff.addFilter("catalogue_id", catalogueName);
        ff.addFilter("active", false);
        ff.setFrom(0);
        ff.setQuantity(maxQuantity);
        ff.addOrderBy("name", "asc");
        return this.getAll(ff, null).getResults();
    }

    private void createLoggingInfoEntriesForResourceExtraUpdates(ServiceBundle bundle, Authentication auth) {
        List<LoggingInfo> loggingInfoList = commonMethods.returnLoggingInfoListAndCreateRegistrationInfoIfEmpty(bundle, auth);
        LoggingInfo loggingInfo = commonMethods.createLoggingInfo(auth, LoggingInfo.Types.UPDATE.getKey(),
                LoggingInfo.ActionType.UPDATED.getKey());
        loggingInfoList.add(loggingInfo);
        bundle.setLoggingInfo(loggingInfoList);
    }

    public FacetFilter createFacetFilterForFetchingServices(Map<String, Object> allRequestParams, String catalogueId){
        FacetFilter ff = FacetFilterUtils.createFacetFilter(allRequestParams);
        allRequestParams.remove("catalogue_id");
        if (catalogueId != null){
            if (!catalogueId.equals("all")){
                ff.addFilter("catalogue_id", catalogueId);
            }
        }
        ff.addFilter("published", false);
        ff.setResourceType("service");
        return ff;
    }

    public FacetFilter createFacetFilterForFetchingServices(MultiValueMap<String, Object> allRequestParams, String catalogueId){
        FacetFilter ff = FacetFilterUtils.createMultiFacetFilter(allRequestParams);
        allRequestParams.remove("catalogue_id");
        if (catalogueId != null){
            if (!catalogueId.equals("all")){
                ff.addFilter("catalogue_id", catalogueId);
            }
        }
        ff.addFilter("published", false);
        ff.setResourceType("service");
        return ff;
    }

    public void updateFacetFilterConsideringTheAuthorization(FacetFilter filter, Authentication auth){
        // if user is Unauthorized, return active/latest ONLY
        if (auth == null) {
            filter.addFilter("active", true);
        }
        if (auth != null && auth.isAuthenticated()) {
            // if user is Authorized with ROLE_USER, return active/latest ONLY
            if (!securityService.hasRole(auth, "ROLE_PROVIDER") && !securityService.hasRole(auth, "ROLE_EPOT") &&
                    !securityService.hasRole(auth, "ROLE_ADMIN")) {
                filter.addFilter("active", true);
            }
        }
    }

    private List<String> getAllProviderIds(){
        FacetFilter ff = new FacetFilter();
        ff.setQuantity(10000);
        ff.addFilter("published", false);
        List<ProviderBundle> allProviders = providerService.getAll(ff, securityService.getAdminAccess()).getResults();
        return allProviders.stream().map(Bundle::getId).collect(Collectors.toList());
    }
}
