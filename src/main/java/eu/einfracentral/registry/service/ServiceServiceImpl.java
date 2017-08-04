package eu.einfracentral.registry.service;

import eu.einfracentral.domain.Service;

import java.io.InputStream;

/**
 * Created by pgl on 4/7/2017.
 */
@org.springframework.stereotype.Service("serviceService")

public class ServiceServiceImpl<T> extends BaseGenericResourceCRUDServiceImpl<Service> implements ServiceService {

    public ServiceServiceImpl() {
        super(Service.class);
    }

    @Override
    public String getResourceType() {
        return "service";
    }

    @Override
    public String uploadService(String filename, InputStream inputStream) {
        return null;
    }

}
