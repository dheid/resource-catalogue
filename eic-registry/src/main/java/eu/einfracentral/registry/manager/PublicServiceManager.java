package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.Identifiers;
import eu.einfracentral.domain.ServiceBundle;
import eu.einfracentral.exception.ResourceException;
import eu.einfracentral.exception.ResourceNotFoundException;
import eu.einfracentral.service.SecurityService;
import eu.einfracentral.utils.JmsService;
import eu.einfracentral.utils.ProviderResourcesCommonMethods;
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

@Service("publicServiceManager")
public class PublicServiceManager extends AbstractPublicResourceManager<ServiceBundle> implements ResourceCRUDService<ServiceBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(PublicServiceManager.class);
    private final JmsService jmsService;
    private final SecurityService securityService;
    private final ProviderResourcesCommonMethods commonMethods;

    @Autowired
    public PublicServiceManager(JmsService jmsService, SecurityService securityService,
                                ProviderResourcesCommonMethods commonMethods) {
        super(ServiceBundle.class);
        this.jmsService = jmsService;
        this.securityService = securityService;
        this.commonMethods = commonMethods;
    }

    @Override
    public String getResourceType() {
        return "service";
    }

    @Override
    public Browsing<ServiceBundle> getAll(FacetFilter facetFilter, Authentication authentication) {
        return super.getAll(facetFilter, authentication);
    }

    @Override
    public Browsing<ServiceBundle> getMy(FacetFilter facetFilter, Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedUserException("Please log in.");
        }

        List<ServiceBundle> serviceBundleList = new ArrayList<>();
        Browsing<ServiceBundle> serviceBundleBrowsing = super.getAll(facetFilter, authentication);
        for (ServiceBundle serviceBundle : serviceBundleBrowsing.getResults()) {
            if (securityService.isResourceProviderAdmin(authentication, serviceBundle.getId(), serviceBundle.getService().getCatalogueId()) && serviceBundle.getMetadata().isPublished()) {
                serviceBundleList.add(serviceBundle);
            }
        }
        return new Browsing<>(serviceBundleBrowsing.getTotal(), serviceBundleBrowsing.getFrom(),
                serviceBundleBrowsing.getTo(), serviceBundleList, serviceBundleBrowsing.getFacets());
    }

    @Override
    public ServiceBundle add(ServiceBundle serviceBundle, Authentication authentication) {
        String lowerLevelResourceId = serviceBundle.getId();
        Identifiers.createOriginalId(serviceBundle);
        serviceBundle.setId(String.format("%s.%s", serviceBundle.getService().getCatalogueId(), serviceBundle.getId()));
        commonMethods.restrictPrefixRepetitionOnPublicResources(serviceBundle.getId(), serviceBundle.getService().getCatalogueId());

        // sets public ids to resource organisation, resource providers and related/required resources
        updateServiceIdsToPublic(serviceBundle);

        serviceBundle.getMetadata().setPublished(true);
        // create PID and set it as Alternative Identifier
        commonMethods.createPIDAndCorrespondingAlternativeIdentifier(serviceBundle, "services/");
        ServiceBundle ret;
        logger.info(String.format("Service [%s] is being published with id [%s]", lowerLevelResourceId, serviceBundle.getId()));
        ret = super.add(serviceBundle, null);
        jmsService.convertAndSendTopic("service.create", serviceBundle);
        return ret;
    }

    @Override
    public ServiceBundle update(ServiceBundle serviceBundle, Authentication authentication) {
        ServiceBundle published = super.get(String.format("%s.%s", serviceBundle.getService().getCatalogueId(), serviceBundle.getId()));
        ServiceBundle ret = super.get(String.format("%s.%s", serviceBundle.getService().getCatalogueId(), serviceBundle.getId()));
        try {
            BeanUtils.copyProperties(ret, serviceBundle);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        // sets public ids to resource organisation, resource providers and related/required resources
        updateServiceIdsToPublic(ret);

        ret.getService().setAlternativeIdentifiers(commonMethods.updateAlternativeIdentifiers(
                serviceBundle.getService().getAlternativeIdentifiers(),
                published.getService().getAlternativeIdentifiers()));
        ret.setIdentifiers(published.getIdentifiers());
        ret.setId(published.getId());
        ret.getMetadata().setPublished(true);
        logger.info(String.format("Updating public Service with id [%s]", ret.getId()));
        ret = super.update(ret, null);
        jmsService.convertAndSendTopic("service.update", ret);
        return ret;
    }

    @Override
    public void delete(ServiceBundle serviceBundle) {
        try{
            ServiceBundle publicServiceBundle = get(String.format("%s.%s", serviceBundle.getService().getCatalogueId(), serviceBundle.getId()));
            logger.info(String.format("Deleting public Service with id [%s]", publicServiceBundle.getId()));
            super.delete(publicServiceBundle);
            jmsService.convertAndSendTopic("service.delete", publicServiceBundle);
        } catch (ResourceException | ResourceNotFoundException ignore){
        }
    }
}
