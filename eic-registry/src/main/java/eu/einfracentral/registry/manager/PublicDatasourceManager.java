package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.DatasourceBundle;
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

@Service("publicDatasourceManager")
public class PublicDatasourceManager extends AbstractPublicResourceManager<DatasourceBundle> implements ResourceCRUDService<DatasourceBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(PublicDatasourceManager.class);
    private final JmsService jmsService;
    private final SecurityService securityService;
    private final ProviderResourcesCommonMethods commonMethods;

    @Autowired
    public PublicDatasourceManager(JmsService jmsService, SecurityService securityService,
                                   ProviderResourcesCommonMethods commonMethods) {
        super(DatasourceBundle.class);
        this.jmsService = jmsService;
        this.securityService = securityService;
        this.commonMethods = commonMethods;
    }

    @Override
    public String getResourceType() {
        return "datasource";
    }

    @Override
    public Browsing<DatasourceBundle> getAll(FacetFilter facetFilter, Authentication authentication) {
        return super.getAll(facetFilter, authentication);
    }

    @Override
    public Browsing<DatasourceBundle> getMy(FacetFilter facetFilter, Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedUserException("Please log in.");
        }

        List<DatasourceBundle> datasourceBundleList = new ArrayList<>();
        Browsing<DatasourceBundle> datasourceBundleBrowsing = super.getAll(facetFilter, authentication);
        for (DatasourceBundle datasourceBundle : datasourceBundleBrowsing.getResults()) {
            if (securityService.isResourceProviderAdmin(authentication, datasourceBundle.getDatasource().getServiceId(), datasourceBundle.getDatasource().getCatalogueId()) && datasourceBundle.getMetadata().isPublished()) {
                datasourceBundleList.add(datasourceBundle);
            }
        }
        return new Browsing<>(datasourceBundleBrowsing.getTotal(), datasourceBundleBrowsing.getFrom(),
                datasourceBundleBrowsing.getTo(), datasourceBundleList, datasourceBundleBrowsing.getFacets());
    }

    public DatasourceBundle getOrElseReturnNull(String id) {
        DatasourceBundle datasourceBundle;
        try {
            datasourceBundle = get(id);
        } catch (ResourceException | ResourceNotFoundException e) {
            return null;
        }
        return datasourceBundle;
    }

    @Override
    public DatasourceBundle add(DatasourceBundle datasourceBundle, Authentication authentication) {
        String lowerLevelResourceId = datasourceBundle.getId();
        Identifiers.createOriginalId(datasourceBundle);
        datasourceBundle.setId(String.format("%s.%s", datasourceBundle.getDatasource().getCatalogueId(), datasourceBundle.getId()));
        commonMethods.restrictPrefixRepetitionOnPublicResources(datasourceBundle.getId(), datasourceBundle.getDatasource().getCatalogueId());

        // sets public ids to providerId, serviceId
        updateDatasourceIdsToPublic(datasourceBundle);

        datasourceBundle.getMetadata().setPublished(true);
        DatasourceBundle ret;
        logger.info(String.format("Datasource [%s] is being published with id [%s]", lowerLevelResourceId, datasourceBundle.getId()));
        ret = super.add(datasourceBundle, null);
        jmsService.convertAndSendTopic("datasource.create", datasourceBundle);
        return ret;
    }

    @Override
    public DatasourceBundle update(DatasourceBundle datasourceBundle, Authentication authentication) {
        DatasourceBundle published = super.get(String.format("%s.%s", datasourceBundle.getDatasource().getCatalogueId(), datasourceBundle.getId()));
        DatasourceBundle ret = super.get(String.format("%s.%s", datasourceBundle.getDatasource().getCatalogueId(), datasourceBundle.getId()));
        try {
            BeanUtils.copyProperties(ret, datasourceBundle);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        // sets public ids to providerId, serviceId
        updateDatasourceIdsToPublic(datasourceBundle);

        ret.setIdentifiers(published.getIdentifiers());
        ret.setId(published.getId());
        ret.getMetadata().setPublished(true);
        logger.info(String.format("Updating public Datasource with id [%s]", ret.getId()));
        ret = super.update(ret, null);
        jmsService.convertAndSendTopic("datasource.update", ret);
        return ret;
    }

    @Override
    public void delete(DatasourceBundle datasourceBundle) {
        try{
            DatasourceBundle publicDatasourceBundle = get(String.format("%s.%s", datasourceBundle.getDatasource().getCatalogueId(), datasourceBundle.getId()));
            logger.info(String.format("Deleting public Datasource with id [%s]", publicDatasourceBundle.getId()));
            super.delete(publicDatasourceBundle);
            jmsService.convertAndSendTopic("datasource.delete", publicDatasourceBundle);
        } catch (ResourceException | ResourceNotFoundException ignore){
        }
    }
}
