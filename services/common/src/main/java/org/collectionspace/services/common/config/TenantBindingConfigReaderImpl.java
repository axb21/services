/**
 *  This document is a part of the source code and related artifacts
 *  for CollectionSpace, an open source collections management system
 *  for museums and related institutions:

 *  http://www.collectionspace.org
 *  http://wiki.collectionspace.org

 *  Copyright 2009 University of California at Berkeley

 *  Licensed under the Educational Community License (ECL), Version 2.0.
 *  You may not use this file except in compliance with this License.

 *  You may obtain a copy of the ECL 2.0 License at

 *  https://source.collectionspace.org/collection-space/LICENSE.txt

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.collectionspace.services.common.config;

import java.io.File;
import java.util.Hashtable;
import org.collectionspace.services.common.ClientType;
import org.collectionspace.services.common.RepositoryClientConfigType;
import org.collectionspace.services.common.ServiceMain;
import org.collectionspace.services.common.repository.RepositoryClient;
import org.collectionspace.services.common.repository.RepositoryClientFactory;
import org.collectionspace.services.common.service.ServiceBindingType;
import org.collectionspace.services.common.tenant.TenantBindingType;
import org.collectionspace.services.common.tenant.TenantBindingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServicesConfigReader reads service layer specific configuration
 *
 * $LastChangedRevision: $
 * $LastChangedDate: $
 */
public class TenantBindingConfigReaderImpl
        extends AbstractConfigReaderImpl<TenantBindingConfig> {

    final private static String CONFIG_FILE_NAME = "tenant-bindings.xml";
    final Logger logger = LoggerFactory.getLogger(TenantBindingConfigReaderImpl.class);
    private TenantBindingConfig tenantBindingConfig;
    //tenant name, tenant binding
    private Hashtable<String, TenantBindingType> tenantBindings =
            new Hashtable<String, TenantBindingType>();
    //tenant-qualified servicename, service binding
    private Hashtable<String, ServiceBindingType> serviceBindings =
            new Hashtable<String, ServiceBindingType>();
    //tenant-qualified service, workspace
    private Hashtable<String, String> serviceWorkspaces = new Hashtable<String, String>();

    public TenantBindingConfigReaderImpl(String serverRootDir) {
        super(serverRootDir);
    }

    @Override
    public String getFileName() {
        return CONFIG_FILE_NAME;
    }

    @Override
    public void read() throws Exception {
        String configFileName = getAbsoluteFileName(CONFIG_FILE_NAME);
        File configFile = new File(configFileName);
        if (!configFile.exists()) {
            String msg = "Could not find configuration file " + configFileName;
            logger.error(msg);
            throw new RuntimeException(msg);
        }
        tenantBindingConfig = (TenantBindingConfig) parse(configFile, TenantBindingConfig.class);
        for (TenantBindingType tenantBinding : tenantBindingConfig.getTenantBinding()) {
            tenantBindings.put(tenantBinding.getId(), tenantBinding);
            readServiceBindings(tenantBinding);
            if (logger.isDebugEnabled()) {
                logger.debug("read() added tenant id=" + tenantBinding.getId()
                        + " name=" + tenantBinding.getName());
            }
        }
    }

    private void readServiceBindings(TenantBindingType tenantBinding) throws Exception {
        for (ServiceBindingType serviceBinding : tenantBinding.getServiceBindings()) {
            String key = getTenantQualifiedServiceName(tenantBinding.getId(),
                    serviceBinding.getName());
            serviceBindings.put(key, serviceBinding);
            if (logger.isDebugEnabled()) {
                logger.debug("readServiceBindings() added service "
                        + " name=" + key
                        + " workspace=" + serviceBinding.getName());
            }
        }
    }

    /**
     * retrieveWorkspaceIds is called at initialization time to retrieve
     * workspace ids of all the tenants
     * @throws Exception
     */
    public void retrieveAllWorkspaceIds() throws Exception {
        for (TenantBindingType tenantBinding : tenantBindings.values()) {
            retrieveWorkspaceIds(tenantBinding);
        }
    }

    /**
     * retrieveWorkspaceIds retrieves workspace ids for services used by
     * the given tenant
     * @param tenantBinding
     * @throws Exception
     */
    public void retrieveWorkspaceIds(TenantBindingType tenantBinding) throws Exception {
        Hashtable<String, String> workspaceIds = new Hashtable<String, String>();
        ServiceMain svcMain = ServiceMain.getInstance();
        RepositoryClientConfigType rclientConfig = svcMain.getServicesConfigReader().getConfiguration().getRepositoryClient();
        ClientType clientType = svcMain.getClientType();
        if (clientType.equals(ClientType.JAVA)
                && rclientConfig.getName().equalsIgnoreCase("nuxeo-java")) {
            //FIXME only one repository client is recognized
            workspaceIds = svcMain.getNuxeoConnector().retrieveWorkspaceIds(
                    tenantBinding.getRepositoryDomain());
        }
        //verify if workspace exists for each service in the tenant binding
        for (ServiceBindingType serviceBinding : tenantBinding.getServiceBindings()) {
            String serviceName = serviceBinding.getName();
            String repositoryClientName = serviceBinding.getRepositoryClient();
            if (repositoryClientName == null) {
                //no repository needed for this service...skip
                if (logger.isDebugEnabled()) {
                    logger.debug("No repository configured for service " + serviceName
                            + " skipping...");
                }
                continue;
            }

            if (repositoryClientName.isEmpty()) {
                String msg = "Invalid repositoryClient " + serviceName;
                logger.error(msg);
                continue;
            }
            repositoryClientName = repositoryClientName.trim();
            RepositoryClient repositoryClient = getRepositoryClient(
                    repositoryClientName);
            if (repositoryClient == null) {
                String msg = "Could not find repositoryClient " + repositoryClientName
                        + " for service=" + serviceName;
                logger.error(msg);
                continue;
            }
            String workspaceId = null;
            //workspace name is service name by convention
            String workspace = serviceBinding.getName().toLowerCase();
            if (clientType.equals(ClientType.JAVA)) {
                workspaceId = workspaceIds.get(workspace);
                if (workspaceId == null) {
                    logger.warn("Failed to retrieve workspace ID for " + workspace);
                    logger.warn("Trying to create a new workspace ...");
                    workspaceId = repositoryClient.createWorkspace(
                            tenantBinding.getRepositoryDomain(),
                            serviceBinding.getName());
                    if (workspaceId == null) {
                        logger.warn("Failed to create workspace for " + workspace);
                        continue;
                    }
                }
            } else {
                workspaceId = serviceBinding.getRepositoryWorkspaceId();
                if (workspaceId == null || "".equals(workspaceId)) {
                    logger.error("Could not find workspace ID for " + workspace);
                    //FIXME: should we throw an exception here?
                    continue;
                }
            }
            String tenantService = getTenantQualifiedServiceName(tenantBinding.getId(), serviceName);
            serviceWorkspaces.put(tenantService, workspaceId);
            if (logger.isDebugEnabled()) {
                logger.debug("retrieved workspace id=" + workspaceId
                        + " service=" + serviceName
                        + " workspace=" + workspace);
            }
        }
    }

    @Override
    public TenantBindingConfig getConfiguration() {
        return tenantBindingConfig;
    }

    /**
     * getTenantBinding gets tenant binding for given tenant
     * @param tenantId
     * @return
     */
    public TenantBindingType getTenantBinding(
            String tenantId) {
        return tenantBindings.get(tenantId);
    }

    /**
     * getServiceBinding gets service binding for given tenant for a given service
     * @param tenantId
     * @param serviceName
     * @return
     */
    public ServiceBindingType getServiceBinding(
            String tenantId, String serviceName) {
        String key = getTenantQualifiedServiceName(tenantId, serviceName);
        return serviceBindings.get(key);
    }

    /**
     * getWorkspaceId retrieves workspace id for given tenant for given service
     * @param tenantId
     * @param serviceName
     * @return
     */
    public String getWorkspaceId(String tenantId, String serviceName) {
        String tenantService = getTenantQualifiedServiceName(tenantId, serviceName);
        return serviceWorkspaces.get(tenantService);
    }

    public static String getTenantQualifiedServiceName(
            String tenantId, String serviceName) {
        return tenantId + "." + serviceName.toLowerCase();
    }

    private RepositoryClient getRepositoryClient(String clientName) {
        return RepositoryClientFactory.getInstance().getClient(clientName);
    }
}