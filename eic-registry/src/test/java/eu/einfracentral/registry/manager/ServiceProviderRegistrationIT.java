package eu.einfracentral.registry.manager;

import eu.einfracentral.config.ServiceConfig;
import eu.einfracentral.domain.InfraService;
import eu.einfracentral.domain.Provider;
import eu.einfracentral.domain.Service;
import eu.einfracentral.exception.ResourceException;
import eu.einfracentral.registry.service.InfraServiceService;
import eu.einfracentral.registry.service.VocabularyService;
import eu.einfracentral.registry.service.ProviderService;
import eu.einfracentral.service.SecurityService;
import eu.openminted.registry.core.exception.ResourceNotFoundException;
import eu.openminted.registry.core.service.ServiceException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.GregorianCalendar;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {ServiceConfig.class})
//@ContextConfiguration(classes = { TestConfig.class })
//@ContextConfiguration(classes = { MockDatabaseConfiguration.class })
@ActiveProfiles("test")
@WebAppConfiguration
public class ServiceProviderRegistrationIT {

    private static final Logger logger = LogManager.getLogger(ServiceProviderRegistrationIT.class);

    @Autowired
    ProviderService<Provider, Authentication> providerService;

    @Autowired
    InfraServiceService<InfraService, InfraService> infraServiceService;

    @Autowired
    VocabularyService vocabularyService;

    @Autowired
    SecurityService securityService;

    @Test
    public void addInvalidProviderTest() throws MalformedURLException {
        Provider provider = null;
        try {
            provider = addProvider("*&*");
        } catch (ServiceException e) {
            logger.info(e);
        }
        assert provider == null;
    }


    @Test
    public void addUpdateAndDeleteProvider() throws ResourceNotFoundException, MalformedURLException {
        String providerId = "test";
        Provider provider;
        InfraService infraService;

        try {

            provider = addProvider(providerId);
            assert provider != null;
            provider = updateProvider(providerId);
            assert provider != null;

            providerService.verifyProvider(providerId, Provider.States.ST_SUBMISSION, true, securityService.getAdminAccess());

            infraService = new InfraService(createService("TestService", provider));

            infraService = infraServiceService.addService(infraService, securityService.getAdminAccess());

            assert infraService != null;

            providerService.verifyProvider(providerId, Provider.States.REJECTED_ST, false, securityService.getAdminAccess());

            infraServiceService.updateService(infraService, securityService.getAdminAccess());

            providerService.verifyProvider(providerId, Provider.States.APPROVED, true, securityService.getAdminAccess());
            providerService.verifyProvider(providerId, Provider.States.APPROVED, false, securityService.getAdminAccess());
            providerService.verifyProvider(providerId, Provider.States.REJECTED, false, securityService.getAdminAccess());

        } catch (ResourceException e) {
            logger.error("ERROR", e);
        } finally {
            provider = providerService.get(providerId, securityService.getAdminAccess());
            logger.info(String.format("Deleting provider with id: %s", provider.getId()));
            providerService.delete(provider);
        }

    }

    private Provider addProvider(String id) throws MalformedURLException {
        Provider provider = new Provider();
        provider.setId(id);
        provider.setName("Test");
        provider.setWebsite(new URL("http://test.t"));

        return providerService.add(provider, securityService.getAdminAccess());
    }

    private Provider updateProvider(String id) throws MalformedURLException, ResourceNotFoundException {
        // get provider
        Provider provider = providerService.get(id);

        // update provider
        return providerService.update(provider, securityService.getAdminAccess());
    }


    public Service createService(String serviceName, Provider provider) throws MalformedURLException {
        Service service = new Service();

        service.setName(serviceName);
        service.setProviders(Collections.singletonList(provider.getId()));
        service.setTrl(vocabularyService.get("trl-7").getId());
        service.setPhase(vocabularyService.get("beta").getId());
        service.setPlaces(Collections.singletonList(vocabularyService.get("WW").getId()));
        service.setLanguages(Collections.singletonList(vocabularyService.get("english").getId()));
        service.setCategory(vocabularyService.get("other-other").getId());

        service.setUrl(new URL("http://test.t"));
        service.setLogo(new URL("http://test.t"));
        service.setOrder(new URL("http://test.t"));
        service.setSla(new URL("http://test.t"));

        service.setDescription("test service");
        service.setVersion("v1.0");
        try {
            service.setLastUpdate(DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
        } catch (DatatypeConfigurationException e) {
            logger.error("ERROR", e);
        }

        return service;
    }
}
