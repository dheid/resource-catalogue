package eu.einfracentral.registry.manager;

import com.google.gson.JsonArray;
import eu.einfracentral.domain.*;
import eu.einfracentral.exception.ValidationException;
import eu.einfracentral.registry.service.ResourceBundleService;
import eu.einfracentral.registry.service.MonitoringService;
import eu.einfracentral.service.RegistrationMailService;
import eu.einfracentral.service.SecurityService;
import eu.einfracentral.utils.ResourceValidationUtils;
import eu.openminted.registry.core.domain.Resource;
import eu.openminted.registry.core.service.SearchService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@org.springframework.stereotype.Service("monitoringManager")
public class MonitoringManager extends ResourceManager<MonitoringBundle> implements MonitoringService<MonitoringBundle, Authentication> {

    private static final Logger logger = LogManager.getLogger(MonitoringManager.class);
    private final ResourceBundleService<ServiceBundle> serviceBundleService;
    private final ResourceBundleService<DatasourceBundle> datasourceBundleService;
    private final JmsTemplate jmsTopicTemplate;
    private final SecurityService securityService;
    private final RegistrationMailService registrationMailService;
    @Value("${argo.grnet.monitoring.token}")
    private String monitoringToken;


    public MonitoringManager(ResourceBundleService<ServiceBundle> serviceBundleService,
                             ResourceBundleService<DatasourceBundle> datasourceBundleService,
                             JmsTemplate jmsTopicTemplate, @Lazy SecurityService securityService,
                             @Lazy RegistrationMailService registrationMailService) {
        super(MonitoringBundle.class);
        this.serviceBundleService = serviceBundleService;
        this.datasourceBundleService = datasourceBundleService;
        this.jmsTopicTemplate = jmsTopicTemplate;
        this.securityService = securityService;
        this.registrationMailService = registrationMailService;
    }

    @Override
    public String getResourceType() {
        return "monitoring";
    }

    @Override
    public MonitoringBundle validate(MonitoringBundle monitoringBundle, String resourceType) {
        String resourceId = monitoringBundle.getMonitoring().getServiceId();
        String catalogueId = monitoringBundle.getCatalogueId();

        MonitoringBundle existingMonitoring = get(resourceId, catalogueId);
        if (existingMonitoring != null) {
            throw new ValidationException(String.format("Resource [%s] of the Catalogue [%s] has already a Monitoring " +
                    "registered, with id: [%s]", resourceId, catalogueId, existingMonitoring.getId()));
        }

        // check if Resource exists and if User belongs to Resource's Provider Admins
        if (resourceType.equals("service")){
            ResourceValidationUtils.checkIfResourceBundleActiveAndApprovedAndNotPublic(resourceId, catalogueId, serviceBundleService, resourceType);
        } else if (resourceType.equals("datasource")){
            ResourceValidationUtils.checkIfResourceBundleActiveAndApprovedAndNotPublic(resourceId, catalogueId, datasourceBundleService, resourceType);
        } else{
            throw new ValidationException("Field resourceType should be either 'service' or 'datasource'");
        }

        super.validate(monitoringBundle);

        // validate serviceType
        serviceTypeValidation(monitoringBundle.getMonitoring());

        return monitoringBundle;
    }

    @Override
    public MonitoringBundle add(MonitoringBundle monitoring, String resourceType, Authentication auth) {
        validate(monitoring, resourceType);

        monitoring.setId(UUID.randomUUID().toString());
        logger.trace("User '{}' is attempting to add a new Monitoring: {}", auth, monitoring);

        monitoring.setMetadata(Metadata.createMetadata(User.of(auth).getFullName(), User.of(auth).getEmail()));
        LoggingInfo loggingInfo = LoggingInfo.createLoggingInfoEntry(User.of(auth).getEmail(), User.of(auth).getFullName(), securityService.getRoleName(auth),
                LoggingInfo.Types.ONBOARD.getKey(), LoggingInfo.ActionType.REGISTERED.getKey());
        List<LoggingInfo> loggingInfoList = new ArrayList<>();
        loggingInfoList.add(loggingInfo);
        monitoring.setLoggingInfo(loggingInfoList);
        monitoring.setActive(true);
        // latestOnboardingInfo
        monitoring.setLatestOnboardingInfo(loggingInfo);

        MonitoringBundle ret;
        ret = super.add(monitoring, null);
        logger.debug("Adding Monitoring: {}", monitoring);

        registrationMailService.sendEmailsForMonitoringExtension(monitoring, "post");
        logger.info("Sending JMS with topic 'monitoring.create'");
        jmsTopicTemplate.convertAndSend("monitoring.create", monitoring);

        return ret;
    }

    @Override
    public MonitoringBundle update(MonitoringBundle monitoring, Authentication auth) {
        logger.trace("User '{}' is attempting to update the Monitoring with id '{}'", auth, monitoring.getId());

        Resource existing = whereID(monitoring.getId(), true);
        MonitoringBundle ex = deserialize(existing);
        // check if there are actual changes in the Monitoring
        if (monitoring.getMonitoring().equals(ex.getMonitoring())){
            throw new ValidationException("There are no changes in the Monitoring", HttpStatus.OK);
        }

        validate(monitoring);
        monitoring.setMetadata(Metadata.updateMetadata(monitoring.getMetadata(), User.of(auth).getFullName(), User.of(auth).getEmail()));
        List<LoggingInfo> loggingInfoList = new ArrayList<>();
        LoggingInfo loggingInfo;
        loggingInfo = LoggingInfo.createLoggingInfoEntry(User.of(auth).getEmail(), User.of(auth).getFullName(), securityService.getRoleName(auth),
                LoggingInfo.Types.UPDATE.getKey(), LoggingInfo.ActionType.UPDATED.getKey());
        if (monitoring.getLoggingInfo() != null) {
            loggingInfoList = monitoring.getLoggingInfo();
            loggingInfoList.add(loggingInfo);
        } else {
            loggingInfoList.add(loggingInfo);
        }
        monitoring.setLoggingInfo(loggingInfoList);

        // latestUpdateInfo
        monitoring.setLatestUpdateInfo(loggingInfo);

        monitoring.setActive(ex.isActive());
        existing.setPayload(serialize(monitoring));
        existing.setResourceType(resourceType);

        // block user from updating serviceId
        if (!monitoring.getMonitoring().getServiceId().equals(ex.getMonitoring().getServiceId()) && !securityService.hasRole(auth, "ROLE_ADMIN")){
            throw new ValidationException("You cannot change the Service Id with which this Monitoring is related");
        }

        resourceService.updateResource(existing);
        logger.debug("Updating Monitoring: {}", monitoring);

        registrationMailService.sendEmailsForMonitoringExtension(monitoring, "put");
        logger.info("Sending JMS with topic 'monitoring.update'");
        jmsTopicTemplate.convertAndSend("monitoring.update", monitoring);

        return monitoring;
    }

    public void delete(MonitoringBundle monitoring, Authentication auth) {
        logger.trace("User '{}' is attempting to delete the Monitoring with id '{}'", auth, monitoring.getId());

        super.delete(monitoring);
        logger.debug("Deleting Monitoring: {}", monitoring);

        //TODO: send emails
        logger.info("Sending JMS with topic 'monitoring.delete'");
        jmsTopicTemplate.convertAndSend("monitoring.delete", monitoring);

    }

    public List<String> getAvailableServiceTypes() {
        List<String> serviceTypeList = new ArrayList<>();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-api-key", "71553f86c28d296daa4b997bd140015cdeacdb66659aae8b2661c098235ef5ff");
        headers.add("Accept", "application/json");
        String url = "https://api.devel.argo.grnet.gr/api/v2/topology/service-types";
        HttpEntity<String> entity = new HttpEntity<>("body", headers);
        String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
        JSONObject obj = new JSONObject(response);
        JSONArray arr = obj.getJSONArray("data");
        for (int i = 0; i < arr.length(); i++) {
            serviceTypeList.add(arr.getJSONObject(i).getString("name"));
        }
        return serviceTypeList;
    }

    @Override
    public MonitoringBundle get(String serviceId, String catalogueId) {
        Resource res = where(false, new SearchService.KeyValue("service_id", serviceId), new SearchService.KeyValue("catalogue_id", catalogueId));
        return res != null ? deserialize(res) : null;
    }

    public void serviceTypeValidation(Monitoring monitoring){
        List<String> serviceTypeList = getAvailableServiceTypes();
        for (MonitoringGroup monitoringGroup : monitoring.getMonitoringGroups()){
            String serviceType = monitoringGroup.getServiceType();
            if (!serviceTypeList.contains(serviceType)){
                throw new ValidationException(String.format("The serviceType you provided is wrong. Available serviceTypes are: '%s'", serviceTypeList));
            }
        }
    }


    // Argo GRNET Monitoring Status methods
    public String createHttpRequest(String url){
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "application/json");
        headers.add("Content-Type", "application/json");
        headers.add("x-api-key", monitoringToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
    }

    public List<MonitoringStatus> createMonitoringAvailabilityObject(JsonArray results){
        List<MonitoringStatus> monitoringStatuses = new ArrayList<>();
        for(int i=0; i<results.size(); i++){
            String date = results.get(i).getAsJsonObject().get("date").getAsString();
            String availability = results.get(i).getAsJsonObject().get("availability").getAsString();
            String reliability = results.get(i).getAsJsonObject().get("reliability").getAsString();
            String unknown = results.get(i).getAsJsonObject().get("unknown").getAsString();
            String uptime = results.get(i).getAsJsonObject().get("uptime").getAsString();
            String downtime = results.get(i).getAsJsonObject().get("downtime").getAsString();
            MonitoringStatus monitoringStatus =
                    new MonitoringStatus(date, availability, reliability, unknown, uptime, downtime);
            monitoringStatuses.add(monitoringStatus);
        }
        return monitoringStatuses;
    }

    public List<MonitoringStatus> createMonitoringStatusObject(JsonArray results){
        List<MonitoringStatus> monitoringStatuses = new ArrayList<>();
        for(int i=0; i<results.size(); i++){
            String timestamp = results.get(i).getAsJsonObject().get("timestamp").getAsString();
            String value = results.get(i).getAsJsonObject().get("value").getAsString();
            MonitoringStatus monitoringStatus =
                    new MonitoringStatus(timestamp, value);
            monitoringStatuses.add(monitoringStatus);
        }
        return monitoringStatuses;
    }
}
