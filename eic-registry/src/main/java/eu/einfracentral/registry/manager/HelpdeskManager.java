package eu.einfracentral.registry.manager;

import eu.einfracentral.domain.*;
import eu.einfracentral.exception.ValidationException;
import eu.einfracentral.registry.service.HelpdeskService;
import eu.einfracentral.registry.service.ResourceBundleService;
import eu.einfracentral.service.RegistrationMailService;
import eu.einfracentral.service.SecurityService;
import eu.einfracentral.utils.ResourceValidationUtils;
import eu.openminted.registry.core.domain.Resource;
import eu.openminted.registry.core.service.SearchService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service("helpdeskManager")
public class HelpdeskManager extends ResourceManager<HelpdeskBundle> implements HelpdeskService<HelpdeskBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(HelpdeskManager.class);
    private final ResourceBundleService<ServiceBundle> serviceBundleService;
    private final ResourceBundleService<DatasourceBundle> datasourceBundleService;
    private final JmsTemplate jmsTopicTemplate;
    private final SecurityService securityService;
    private final RegistrationMailService registrationMailService;

    @Autowired
    public HelpdeskManager(ResourceBundleService<ServiceBundle> serviceBundleService,
                           ResourceBundleService<DatasourceBundle> datasourceBundleService,
                           JmsTemplate jmsTopicTemplate, @Lazy SecurityService securityService,
                           @Lazy RegistrationMailService registrationMailService) {
        super(HelpdeskBundle.class);
        this.serviceBundleService = serviceBundleService;
        this.datasourceBundleService = datasourceBundleService;
        this.jmsTopicTemplate = jmsTopicTemplate;
        this.securityService = securityService;
        this.registrationMailService = registrationMailService;
    }

    @Override
    public String getResourceType() {
        return "helpdesk";
    }

    @Override
    public HelpdeskBundle validate(HelpdeskBundle helpdeskBundle, String resourceType) {
        String resourceId = helpdeskBundle.getHelpdesk().getServiceId();
        String catalogueId = helpdeskBundle.getCatalogueId();

        HelpdeskBundle existingHelpdesk = get(resourceId, catalogueId);
        if (existingHelpdesk != null) {
            throw new ValidationException(String.format("Resource [%s] of the Catalogue [%s] has already a Helpdesk " +
                    "registered, with id: [%s]", resourceId, catalogueId, existingHelpdesk.getId()));
        }

        // check if Resource exists and if User belongs to Resource's Provider Admins
        if (resourceType.equals("service")){
            ResourceValidationUtils.checkIfResourceBundleActiveAndApprovedAndNotPublic(resourceId, catalogueId, serviceBundleService, resourceType);
        } else if (resourceType.equals("datasource")){
            ResourceValidationUtils.checkIfResourceBundleActiveAndApprovedAndNotPublic(resourceId, catalogueId, datasourceBundleService, resourceType);
        } else{
            throw new ValidationException("Field resourceType should be either 'service' or 'datasource'");
        }
        return super.validate(helpdeskBundle);
    }

    @Override
    public HelpdeskBundle add(HelpdeskBundle helpdesk, String resourceType, Authentication auth) {
        validate(helpdesk, resourceType);

        helpdesk.setId(UUID.randomUUID().toString());
        logger.trace("User '{}' is attempting to add a new Helpdesk: {}", auth, helpdesk);

        helpdesk.setMetadata(Metadata.createMetadata(User.of(auth).getFullName(), User.of(auth).getEmail()));
        LoggingInfo loggingInfo = LoggingInfo.createLoggingInfoEntry(User.of(auth).getEmail(), User.of(auth).getFullName(), securityService.getRoleName(auth),
                LoggingInfo.Types.ONBOARD.getKey(), LoggingInfo.ActionType.REGISTERED.getKey());
        List<LoggingInfo> loggingInfoList = new ArrayList<>();
        loggingInfoList.add(loggingInfo);
        helpdesk.setLoggingInfo(loggingInfoList);
        helpdesk.setActive(true);
        // latestOnboardingInfo
        helpdesk.setLatestOnboardingInfo(loggingInfo);

        super.add(helpdesk, null);
        logger.debug("Adding Helpdesk: {}", helpdesk);

        registrationMailService.sendEmailsForHelpdeskExtension(helpdesk, "post");

        return helpdesk;
    }

    @Override
    public HelpdeskBundle get(String serviceId, String catalogueId) {
        Resource res = where(false, new SearchService.KeyValue("service_id", serviceId), new SearchService.KeyValue("catalogue_id", catalogueId));
        return res != null ? deserialize(res) : null;
    }

    @Override
    public HelpdeskBundle update(HelpdeskBundle helpdesk, Authentication auth) {
        logger.trace("User '{}' is attempting to update the Helpdesk with id '{}'", auth, helpdesk.getId());

        Resource existing = whereID(helpdesk.getId(), true);
        HelpdeskBundle ex = deserialize(existing);
        // check if there are actual changes in the Helpdesk
        if (helpdesk.getHelpdesk().equals(ex.getHelpdesk())){
            throw new ValidationException("There are no changes in the Helpdesk", HttpStatus.OK);
        }

        validate(helpdesk);
        helpdesk.setMetadata(Metadata.updateMetadata(helpdesk.getMetadata(), User.of(auth).getFullName(), User.of(auth).getEmail()));
        List<LoggingInfo> loggingInfoList = new ArrayList<>();
        LoggingInfo loggingInfo;
        loggingInfo = LoggingInfo.createLoggingInfoEntry(User.of(auth).getEmail(), User.of(auth).getFullName(), securityService.getRoleName(auth),
                LoggingInfo.Types.UPDATE.getKey(), LoggingInfo.ActionType.UPDATED.getKey());
        if (helpdesk.getLoggingInfo() != null) {
            loggingInfoList = helpdesk.getLoggingInfo();
            loggingInfoList.add(loggingInfo);
        } else {
            loggingInfoList.add(loggingInfo);
        }
        helpdesk.setLoggingInfo(loggingInfoList);

        // latestUpdateInfo
        helpdesk.setLatestUpdateInfo(loggingInfo);

        helpdesk.setActive(ex.isActive());
        existing.setPayload(serialize(helpdesk));
        existing.setResourceType(resourceType);

        // block user from updating serviceId
        if (!helpdesk.getHelpdesk().getServiceId().equals(ex.getHelpdesk().getServiceId()) && !securityService.hasRole(auth, "ROLE_ADMIN")){
            throw new ValidationException("You cannot change the Service Id with which this Helpdesk is related");
        }

        resourceService.updateResource(existing);
        logger.debug("Updating Helpdesk: {}", helpdesk);

        registrationMailService.sendEmailsForHelpdeskExtension(helpdesk, "put");

        return helpdesk;
    }

    @Override
    public void delete(HelpdeskBundle helpdesk) {
        super.delete(helpdesk);
        logger.debug("Deleting Helpdesk: {}", helpdesk);
    }
}
