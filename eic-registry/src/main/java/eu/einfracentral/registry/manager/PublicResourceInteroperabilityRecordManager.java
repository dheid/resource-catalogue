package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.Identifiers;
import eu.einfracentral.domain.ResourceInteroperabilityRecordBundle;
import eu.einfracentral.exception.ResourceException;
import eu.einfracentral.exception.ResourceNotFoundException;
import eu.einfracentral.service.SecurityService;
import eu.einfracentral.utils.JmsService;
import eu.openminted.registry.core.domain.Browsing;
import eu.openminted.registry.core.domain.FacetFilter;
import eu.openminted.registry.core.service.ResourceCRUDService;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.exceptions.UnauthorizedUserException;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

@Service("publicResourceInteroperabilityRecordManager")
public class PublicResourceInteroperabilityRecordManager extends AbstractPublicResourceManager<ResourceInteroperabilityRecordBundle>
        implements ResourceCRUDService<ResourceInteroperabilityRecordBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(PublicResourceInteroperabilityRecordManager.class);
    private final JmsService jmsService;
    private final SecurityService securityService;

    @Autowired
    public PublicResourceInteroperabilityRecordManager(JmsService jmsService, SecurityService securityService) {
        super(ResourceInteroperabilityRecordBundle.class);
        this.jmsService = jmsService;
        this.securityService = securityService;
    }

    @Override
    public String getResourceType() {
        return "resource_interoperability_record";
    }

    @Override
    public Browsing<ResourceInteroperabilityRecordBundle> getAll(FacetFilter facetFilter, Authentication authentication) {
        return super.getAll(facetFilter, authentication);
    }

    @Override
    public Browsing<ResourceInteroperabilityRecordBundle> getMy(FacetFilter facetFilter, Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedUserException("Please log in.");
        }

        List<ResourceInteroperabilityRecordBundle> resourceInteroperabilityRecordBundleList = new ArrayList<>();
        Browsing<ResourceInteroperabilityRecordBundle> resourceInteroperabilityRecordBundleBrowsing = super.getAll(facetFilter, authentication);
        for (ResourceInteroperabilityRecordBundle resourceInteroperabilityRecordBundle : resourceInteroperabilityRecordBundleBrowsing.getResults()) {
            if (securityService.isResourceProviderAdmin(authentication, resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getResourceId(),
                    resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getCatalogueId()) && resourceInteroperabilityRecordBundle.getMetadata().isPublished()) {
                resourceInteroperabilityRecordBundleList.add(resourceInteroperabilityRecordBundle);
            }
        }
        return new Browsing<>(resourceInteroperabilityRecordBundleBrowsing.getTotal(), resourceInteroperabilityRecordBundleBrowsing.getFrom(),
                resourceInteroperabilityRecordBundleBrowsing.getTo(), resourceInteroperabilityRecordBundleList, resourceInteroperabilityRecordBundleBrowsing.getFacets());
    }

    @Override
    public ResourceInteroperabilityRecordBundle add(ResourceInteroperabilityRecordBundle resourceInteroperabilityRecordBundle, Authentication authentication) {
        String lowerLevelResourceId = resourceInteroperabilityRecordBundle.getId();
        Identifiers.createOriginalId(resourceInteroperabilityRecordBundle);
        resourceInteroperabilityRecordBundle.setId(String.format("%s.%s", resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getCatalogueId(),
                resourceInteroperabilityRecordBundle.getId()));

        // sets public ids to resourceId and interoperabilityRecordIds
        updateResourceInteroperabilityRecordIdsToPublic(resourceInteroperabilityRecordBundle);

        resourceInteroperabilityRecordBundle.getMetadata().setPublished(true);
        ResourceInteroperabilityRecordBundle ret;
        logger.info(String.format("ResourceInteroperabilityRecordBundle [%s] is being published with id [%s]", lowerLevelResourceId, resourceInteroperabilityRecordBundle.getId()));
        ret = super.add(resourceInteroperabilityRecordBundle, null);
        jmsService.convertAndSendTopic("resource_interoperability_record.create", resourceInteroperabilityRecordBundle);
        return ret;
    }

    @Override
    public ResourceInteroperabilityRecordBundle update(ResourceInteroperabilityRecordBundle resourceInteroperabilityRecordBundle, Authentication authentication) {
        ResourceInteroperabilityRecordBundle published = super.get(String.format("%s.%s", resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getCatalogueId(), resourceInteroperabilityRecordBundle.getId()));
        ResourceInteroperabilityRecordBundle ret = super.get(String.format("%s.%s", resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getCatalogueId(), resourceInteroperabilityRecordBundle.getId()));
        try {
            BeanUtils.copyProperties(ret, resourceInteroperabilityRecordBundle);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        // sets public ids to resourceId and interoperabilityRecordIds
        updateResourceInteroperabilityRecordIdsToPublic(ret);

        ret.setIdentifiers(published.getIdentifiers());
        ret.setId(published.getId());
        ret.getMetadata().setPublished(true);
        logger.info(String.format("Updating public ResourceInteroperabilityRecordBundle with id [%s]", ret.getId()));
        ret = super.update(ret, null);
        jmsService.convertAndSendTopic("resource_interoperability_record.update", ret);
        return ret;
    }

    @Override
    public void delete(ResourceInteroperabilityRecordBundle resourceInteroperabilityRecordBundle) {
        try{
            ResourceInteroperabilityRecordBundle publicResourceInteroperabilityRecordBundle = get(String.format("%s.%s",
                    resourceInteroperabilityRecordBundle.getResourceInteroperabilityRecord().getCatalogueId(), resourceInteroperabilityRecordBundle.getId()));
            logger.info(String.format("Deleting public ResourceInteroperabilityRecordBundle with id [%s]", publicResourceInteroperabilityRecordBundle.getId()));
            super.delete(publicResourceInteroperabilityRecordBundle);
            jmsService.convertAndSendTopic("resource_interoperability_record.delete", publicResourceInteroperabilityRecordBundle);
        } catch (ResourceException | ResourceNotFoundException ignore){
        }
    }

}
