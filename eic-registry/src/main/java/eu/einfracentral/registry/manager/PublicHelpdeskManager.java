package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.HelpdeskBundle;
import eu.einfracentral.domain.Identifiers;
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

@Service("publicHelpdeskManager")
public class PublicHelpdeskManager extends AbstractPublicResourceManager<HelpdeskBundle> implements ResourceCRUDService<HelpdeskBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(PublicDatasourceManager.class);
    private final JmsService jmsService;
    private final SecurityService securityService;
    private final ProviderResourcesCommonMethods commonMethods;

    @Autowired
    public PublicHelpdeskManager(JmsService jmsService, SecurityService securityService,
                                 ProviderResourcesCommonMethods commonMethods) {
        super(HelpdeskBundle.class);
        this.jmsService = jmsService;
        this.securityService = securityService;
        this.commonMethods = commonMethods;
    }

    @Override
    public String getResourceType() {
        return "helpdesk";
    }

    @Override
    public Browsing<HelpdeskBundle> getAll(FacetFilter facetFilter, Authentication authentication) {
        return super.getAll(facetFilter, authentication);
    }

    @Override
    public Browsing<HelpdeskBundle> getMy(FacetFilter facetFilter, Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedUserException("Please log in.");
        }

        List<HelpdeskBundle> helpdeskBundleList = new ArrayList<>();
        Browsing<HelpdeskBundle> helpdeskBundleBrowsing = super.getAll(facetFilter, authentication);
        for (HelpdeskBundle helpdeskBundle : helpdeskBundleBrowsing.getResults()) {
            if (securityService.isResourceProviderAdmin(authentication, helpdeskBundle.getHelpdesk().getServiceId(), helpdeskBundle.getCatalogueId())
                    && helpdeskBundle.getMetadata().isPublished()) {
                helpdeskBundleList.add(helpdeskBundle);
            }
        }
        return new Browsing<>(helpdeskBundleBrowsing.getTotal(), helpdeskBundleBrowsing.getFrom(),
                helpdeskBundleBrowsing.getTo(), helpdeskBundleList, helpdeskBundleBrowsing.getFacets());
    }

    public HelpdeskBundle getOrElseReturnNull(String id) {
        HelpdeskBundle helpdeskBundle;
        try {
            helpdeskBundle = get(id);
        } catch (ResourceException | ResourceNotFoundException e) {
            return null;
        }
        return helpdeskBundle;
    }

    @Override
    public HelpdeskBundle add(HelpdeskBundle helpdeskBundle, Authentication authentication) {
        String lowerLevelResourceId = helpdeskBundle.getId();
        Identifiers.createOriginalId(helpdeskBundle);
        helpdeskBundle.setId(String.format("%s.%s", helpdeskBundle.getCatalogueId(), helpdeskBundle.getId()));
        commonMethods.restrictPrefixRepetitionOnPublicResources(helpdeskBundle.getId(), helpdeskBundle.getCatalogueId());

        // sets public id to serviceId
        updateHelpdeskIdsToPublic(helpdeskBundle);

        helpdeskBundle.getMetadata().setPublished(true);
        HelpdeskBundle ret;
        logger.info(String.format("Helpdesk [%s] is being published with id [%s]", lowerLevelResourceId, helpdeskBundle.getId()));
        ret = super.add(helpdeskBundle, null);
        jmsService.convertAndSendTopic("helpdesk.create", helpdeskBundle);
        return ret;
    }

    @Override
    public HelpdeskBundle update(HelpdeskBundle helpdeskBundle, Authentication authentication) {
        HelpdeskBundle published = super.get(String.format("%s.%s", helpdeskBundle.getCatalogueId(), helpdeskBundle.getId()));
        HelpdeskBundle ret = super.get(String.format("%s.%s", helpdeskBundle.getCatalogueId(), helpdeskBundle.getId()));
        try {
            BeanUtils.copyProperties(ret, helpdeskBundle);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        // sets public id to serviceId
        updateHelpdeskIdsToPublic(helpdeskBundle);

        ret.setIdentifiers(published.getIdentifiers());
        ret.setId(published.getId());
        ret.getMetadata().setPublished(true);
        logger.info(String.format("Updating public Helpdesk with id [%s]", ret.getId()));
        ret = super.update(ret, null);
        jmsService.convertAndSendTopic("helpdesk.update", helpdeskBundle);
        return ret;
    }

    @Override
    public void delete(HelpdeskBundle helpdeskBundle) {
        try{
            HelpdeskBundle publicHelpdeskBundle = get(String.format("%s.%s", helpdeskBundle.getCatalogueId(), helpdeskBundle.getId()));
            logger.info(String.format("Deleting public Helpdesk with id [%s]", publicHelpdeskBundle.getId()));
            super.delete(publicHelpdeskBundle);
            jmsService.convertAndSendTopic("helpdesk.delete", publicHelpdeskBundle);
        } catch (ResourceException | ResourceNotFoundException ignore){
        }
    }
}
