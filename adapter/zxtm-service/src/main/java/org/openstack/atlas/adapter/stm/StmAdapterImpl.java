package org.openstack.atlas.adapter.stm;

import org.apache.axis.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.adapter.LoadBalancerEndpointConfiguration;
import org.openstack.atlas.adapter.exceptions.InsufficientRequestException;
import org.openstack.atlas.adapter.exceptions.StmRollBackException;
import org.openstack.atlas.adapter.exceptions.ZxtmRollBackException;
import org.openstack.atlas.adapter.helpers.ResourceTranslator;
import org.openstack.atlas.adapter.helpers.TrafficScriptHelper;
import org.openstack.atlas.adapter.helpers.ZxtmNameBuilder;
import org.openstack.atlas.adapter.service.ReverseProxyLoadBalancerAdapter;
import org.openstack.atlas.adapter.zxtm.ZxtmServiceStubs;
import org.openstack.atlas.service.domain.entities.*;
import org.openstack.atlas.service.domain.pojos.Hostssubnet;
import org.openstack.atlas.service.domain.pojos.Stats;
import org.openstack.atlas.service.domain.pojos.ZeusSslTermination;
import org.openstack.atlas.service.domain.util.Constants;
import org.rackspace.stingray.client.StingrayRestClient;
import org.rackspace.stingray.client.exception.StingrayRestClientException;
import org.rackspace.stingray.client.exception.StingrayRestClientObjectNotFoundException;
import org.rackspace.stingray.client.monitor.Monitor;
import org.rackspace.stingray.client.persistence.Persistence;
import org.rackspace.stingray.client.pool.Pool;
import org.rackspace.stingray.client.protection.Protection;
import org.rackspace.stingray.client.traffic.ip.TrafficIp;
import org.rackspace.stingray.client.virtualserver.VirtualServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class StmAdapterImpl implements ReverseProxyLoadBalancerAdapter {
    public static Log LOG = LogFactory.getLog(StmAdapterImpl.class.getName());

    //TODO: move to a 'constants' file...
    public static final LoadBalancerAlgorithm DEFAULT_ALGORITHM = LoadBalancerAlgorithm.RANDOM;
    public static final String XFF = "add_x_forwarded_for_header";
    public static final String XFP = "add_x_forwarded_proto";
    public static final String SOURCE_IP = "ip";
    public static final String HTTP_COOKIE = "cookie";


    public StingrayRestClient loadSTMRestClient(LoadBalancerEndpointConfiguration config) throws StmRollBackException {
        StingrayRestClient client = null;
        try {
            client = new StingrayRestClient(new URI(config.getEndpointUrl().toString()));
        } catch (URISyntaxException e) {
            LOG.error(String.format("Configuration error, verify soapendpoint is valid! Exception %s", e));
            throw new StmRollBackException("Configuration error: ", e);
        }
        return client;
    }

    // ** START Temporary for testing purposes
    public StingrayRestClient getStingrayClient() {
        return new StingrayRestClient();
    }
    // ** END Temporary for testing purposes

    @Deprecated
    @Override
    public ZxtmServiceStubs getServiceStubs(LoadBalancerEndpointConfiguration config) throws AxisFault {
        return null;
    }

    @Override
    public void createLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws RemoteException, InsufficientRequestException, StmRollBackException {
        //Forward it for now, can update interface and remove this...
        updateLoadBalancer(config, loadBalancer);
    }

    @Override
    public void updateLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws RemoteException, InsufficientRequestException, StmRollBackException {

        final String virtualServerName = ZxtmNameBuilder.genVSName(loadBalancer);
        StingrayRestClient client = loadSTMRestClient(config);

        ResourceTranslator translator = new ResourceTranslator();

        try {

            if (loadBalancer.getProtocol().equals(LoadBalancerProtocol.HTTP)) {
                TrafficScriptHelper.addXForwardedForScriptIfNeeded(client);
                TrafficScriptHelper.addXForwardedProtoScriptIfNeeded(client);
//                setDefaultErrorFile(config, lb);
            }

            translator.translateLoadBalancerResource(config, virtualServerName, loadBalancer);

            if (loadBalancer.getHealthMonitor() != null && !loadBalancer.hasSsl()) {
                updateHealthMonitor(config, client, virtualServerName, translator.getcMonitor());
            }

//            if (loadBalancer.getSessionPersistence() != null
//                    && !loadBalancer.getSessionPersistence().equals(SessionPersistence.NONE)
//                    && !loadBalancer.hasSsl()) //setSessionPersistence(config, loadBalancer);
//
//            if (loadBalancer.getConnectionLimit() != null) //updateConnectionThrottle(config, loadBalancer);
//
//
//            if (loadBalancer.isContentCaching() != null && loadBalancer.isContentCaching()) //updateContentCaching(config, loadBalancer);
//
//            if (loadBalancer.getAccessLists() != null && !loadBalancer.getAccessLists().isEmpty()) //updateAccessList(config, loadBalancer);

            updateNodePool(config, client, virtualServerName, translator.getcPool());
            createVirtualServer(config, client, virtualServerName, translator.getcVServer());
        } catch (Exception ex) {
            //TODO: roll back or handle as needed.. ...
        }
        //Finish...
    }

    @Override
    public void deleteLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }


    /*
       Virtual Server Resources
    */

    //This is done so the 'parent' or general methods i.e create/update loadbalancer can update all of the 'loadbalancer'
    //components without having to duplicate code while still keeping individual resource operations in tact. i.e. updateHealthMonitor.
    //This will also help with updates vs creates from the interface which will need to be handled at some point for 'clean up'
    private void createVirtualServer(LoadBalancerEndpointConfiguration config,
                                     StingrayRestClient client, String vsName, VirtualServer virtualServer)
            throws StmRollBackException {

        LOG.debug(String.format("Updating  virtual server '%s'...", vsName));

        VirtualServer curVs = null;


        try {
            curVs = client.getVirtualServer(vsName);
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.warn(String.format("Object not found when updating virtual server: %s, this is expected...", virtualServer));
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Error when retrieving pool: %s: ignoring...", virtualServer));
        }

        try {
            client.createVirtualServer(vsName, virtualServer);
        } catch (Exception ex) {
            LOG.error(String.format("Error updating virtual server: %s Rolling back! \n Exception: %s Trace: %s"
                    , vsName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));

            rollbackVirtualServer(client, vsName, virtualServer);
        }
    }

    //DELETE


    private void rollbackVirtualServer(StingrayRestClient client, String vsName, VirtualServer curVs) throws StmRollBackException {
        try {
            if (curVs != null) {
                LOG.debug(String.format("Updating virtual server for rollback '%s'", vsName));
                //TODO: should call method for reuse and logging
                client.updateVirtualServer(vsName, curVs);
            } else {
                LOG.debug(String.format("Deleting virtual server for rollback '%s' ", vsName));
                //TODO: should call method
                client.deleteVirtualServer(vsName);
            }
        } catch (StingrayRestClientException ex) {
            LOG.error(String.format("Error update virtual server: %s Rolling back! \n Exception: %s Trace: %s"
                    , vsName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));
            throw new StmRollBackException(String.format("Error creating pool: %s Rolling back! \n Exception: %s Trace: %s"
                    , vsName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())), ex);
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.warn(String.format("Object not found when update virtual server VS: %s, this is expected...", vsName));
        }
        LOG.debug(String.format("Successfully rolled back pool '%s' ", vsName));
    }


    /*
        Pool Resources
     */
    @Override
    public void setNodes(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws RemoteException, InsufficientRequestException, ZxtmRollBackException, StmRollBackException {

        final String poolName = ZxtmNameBuilder.genVSName(loadBalancer);
        ResourceTranslator translator = new ResourceTranslator();
        StingrayRestClient client = loadSTMRestClient(config);
        translator.translateLoadBalancerResource(config, poolName, loadBalancer);
        updateNodePool(config, client, poolName, translator.getcPool());

    }

    private void updateNodePool(LoadBalancerEndpointConfiguration config,
                                StingrayRestClient client, String poolName, Pool pool)
            throws StmRollBackException {

        LOG.debug(String.format("Creating pool '%s' and setting nodes...", poolName));

        Pool curPool = null;


        try {
            curPool = client.getPool(poolName);
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.warn(String.format("Object not found when creating pool: %s, this is expected...", poolName));
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Error when retrieving pool: %s: ignoring...", poolName));
        }

        try {
            client.createPool(poolName, pool);
        } catch (Exception ex) {
            LOG.error(String.format("Error creating pool: %s Rolling back! \n Exception: %s Trace: %s"
                    , poolName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));

            rollbackPool(client, poolName, curPool);

        }
    }

    //Delelete

    @Override
    public void removeNodes(LoadBalancerEndpointConfiguration config, Integer lbId, Integer accountId, Collection<Node> nodes) throws AxisFault, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    //Take in a Node?
    @Override
    public void removeNode(LoadBalancerEndpointConfiguration config, Integer loadBalancerId, Integer accountId, String ipAddress, Integer port) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    private void rollbackPool(StingrayRestClient client, String poolName, Pool curPool) throws StmRollBackException {
        try {
            if (curPool != null) {
                LOG.debug(String.format("Updating pool for rollback '%s'", poolName));
                //TODO: should call method for reuse and logging
                client.updatePool(poolName, curPool);
            } else {
                LOG.debug(String.format("Deleting pool for rollback '%s' ", poolName));
                //TODO: should call method
                client.deletePool(poolName);
            }
        } catch (StingrayRestClientException ex) {
            LOG.error(String.format("Error creating pool: %s Rolling back! \n Exception: %s Trace: %s"
                    , poolName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));
            throw new StmRollBackException(String.format("Error creating pool: %s Rolling back! \n Exception: %s Trace: %s"
                    , poolName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())), ex);
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.warn(String.format("Object not found when creating pool: %s, this is expected...", poolName));
        }
        LOG.debug(String.format("Successfully rolled back pool '%s' ", poolName));
    }

    /*
        VirtualIP Resources
     */


    @Override
    public void addVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
    }

    @Override
    public void deleteVirtualIp(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, Integer vipId) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, List<Integer> vipId) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    public void addVirtualIps(LoadBalancerEndpointConfiguration config,
                              StingrayRestClient client, String vsName, TrafficIp trafficIpGroup)
            throws RemoteException, InsufficientRequestException, ZxtmRollBackException {

    }

    /*
        Monitor Resources
     */

    @Override
    public void updateHealthMonitor(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws RemoteException, InsufficientRequestException, StmRollBackException {

        final String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        ResourceTranslator translator = new ResourceTranslator();
        StingrayRestClient client = loadSTMRestClient(config);

        translator.translateLoadBalancerResource(config, vsName, loadBalancer);
        updateHealthMonitor(config, client, vsName, translator.getcMonitor());
        //Monitor is a Pool object, update it...
        updateNodePool(config, client, vsName, translator.getcPool());
    }

    @Override
    public void updateHealthMonitor(LoadBalancerEndpointConfiguration config, int lbId, int accountId, HealthMonitor healthMonitor) throws RemoteException, InsufficientRequestException, StmRollBackException {
    }

    @Override
    public void removeHealthMonitor(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    private void updateHealthMonitor(LoadBalancerEndpointConfiguration config,
                                     StingrayRestClient client, String monitorName, Monitor monitor)
            throws StmRollBackException {

        LOG.debug(String.format("Update Monitor '%s' ...", monitor));

        Monitor curMon = null;

        try {
            curMon = client.getMonitor(monitorName);
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.warn(String.format("Object not found when creating pool: %s, this is expected...", monitorName));
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Error when retrieving pool: %s: ignoring...", monitorName));
        }

        try {
            client.updateMonitor(monitorName, monitor);
        } catch (Exception ex) {
            LOG.error(String.format("Error updating monitor: %s Rolling back! \n Exception: %s Trace: %s"
                    , monitorName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));

            rollbackMonitor(client, monitorName, curMon);
        }
    }

    //Delete

    private void rollbackMonitor(StingrayRestClient client, String monitorName, Monitor curMonitor) throws StmRollBackException {
        try {
            if (curMonitor != null) {
                LOG.debug(String.format("Updating monitor for rollback '%s'", monitorName));
                //TODO: should call method for reuse and logging
                client.updateMonitor(monitorName, curMonitor);
            } else {
                LOG.debug(String.format("Deleting monitor for rollback '%s' ", monitorName));
                //TODO: should call method
                client.deleteMonitor(monitorName);
            }
        } catch (StingrayRestClientException ex) {
            LOG.error(String.format("Error updating monitor: %s Rolling back! \n Exception: %s Trace: %s"
                    , monitorName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));
            throw new StmRollBackException(String.format("Error updating monitor: %s Rolling back! \n Exception: %s Trace: %s"
                    , monitorName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())), ex);
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.warn(String.format("Object not found when creating pool: %s, this is expected...", monitorName));
        }
        LOG.debug(String.format("Successfully rolled back monitor '%s' ", monitorName));
    }

    /*
        Connection Logging Resources
     */

    @Override
    public void updateConnectionLogging(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, ZxtmRollBackException, StmRollBackException {
        String vsName = ZxtmNameBuilder.genVSName(loadBalancer);
        ResourceTranslator translator = new ResourceTranslator();
        StingrayRestClient client = loadSTMRestClient(config);
        translator.translateLoadBalancerResource(config, vsName, loadBalancer);

        updateConnectionLogging(config, client, vsName, translator.getcVServer());

    }

    private void updateConnectionLogging(LoadBalancerEndpointConfiguration config,
                                         StingrayRestClient client, String vsName, VirtualServer virtualServer)
            throws StmRollBackException {

        LOG.debug(String.format("Update Virtual Server '%s' and set ConnectionLogging ...", virtualServer));

        try {
            client.updateVirtualServer(vsName, virtualServer);
        } catch (Exception ex) {
            LOG.error(String.format("Error updating virtual server: %s Rolling back! \n Exception: %s Trace: %s"
                    , vsName, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));

//            rollbackLogging(client, monitorName, curMon);
        }
    }


    //DELETE connection logging...

    @Override
    public void updateProtocol(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void updateHalfClosed(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void updatePort(LoadBalancerEndpointConfiguration config, Integer loadBalancerId, Integer accountId, Integer port) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void updateTimeout(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setLoadBalancingAlgorithm(LoadBalancerEndpointConfiguration config, Integer loadBalancerId, Integer accountId, LoadBalancerAlgorithm algorithm) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //TODO: still in example phase...
//
    }

    @Override
    public void changeHostForLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, Host newHost) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }


    @Override
    public void setNodeWeights(LoadBalancerEndpointConfiguration config,
                               Integer loadBalancerId, Integer accountId, Collection<Node> nodes)
            throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
    }


    private boolean persistenceIsSupported(LoadBalancer loadBalancer) {
        boolean supported = false;
        String type = loadBalancer.getSessionPersistence().getSessionPersistence().getPersistenceType().value();
        if (type.equals(HTTP_COOKIE) || type.equals(SOURCE_IP)) {
            supported = true;
        }
        return supported;

    }

    //Because le interface is annoying
    public void setSessionPersistence(LoadBalancerEndpointConfiguration config, Integer lbId, Integer accountId, SessionPersistence mode)
            throws RemoteException, InsufficientRequestException, StmRollBackException {
    }

    public void removeSessionPersistence(LoadBalancerEndpointConfiguration config, Integer lbId, Integer accountId)
            throws RemoteException, InsufficientRequestException, StmRollBackException {
    }


    @Override
    public void setSessionPersistence(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException, StmRollBackException {


        StingrayRestClient client = loadSTMRestClient(config);
        ResourceTranslator translator = new ResourceTranslator();
        String vsName;
        if (!lb.hasSsl() && lb.getSessionPersistence() != null && persistenceIsSupported(lb)) {
            vsName = ZxtmNameBuilder.genVSName(lb);
            translator.translateLoadBalancerResource(config, vsName, lb);
            Persistence persistence = translator.getcPersistence();
            String persistenceType = persistence.getProperties().getBasic().getType();
            try {
                client.createPersistence(persistenceType, persistence);
            } catch (StingrayRestClientObjectNotFoundException onf) {

            } catch (StingrayRestClientException ex) {
                LOG.error(String.format("Error creating session persistence: %s, Rolling back! \n Exception: %s Trace: %s"
                        , persistenceType, ex.getCause().getMessage(), Arrays.toString(ex.getCause().getStackTrace())));
            }
        }
    }


    @Override
    public void removeSessionPersistence(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, StmRollBackException {

        StingrayRestClient client = loadSTMRestClient(config);
        ResourceTranslator translator = new ResourceTranslator();
        String vsName;

        if (!loadBalancer.hasSsl() && loadBalancer.getSessionPersistence() != null && persistenceIsSupported(loadBalancer)) {
            vsName = ZxtmNameBuilder.genVSName(loadBalancer);

            Persistence persistence = translator.translatePersistenceResource(vsName, loadBalancer);
            String persistenceType = persistence.getProperties().getBasic().getType();
            try {
                client.deletePersistence(persistenceType);
                LOG.info("Successfully deleted session persistence " + persistenceType);
            } catch (StingrayRestClientObjectNotFoundException onf) {
                LOG.warn(String.format("Cannot delete persistence as client does not exist", persistenceType));
            } catch (StingrayRestClientException ex) {
                LOG.error("Unexpected client error when deleting session persistence.");
            }
        }
    }


    @Override
    public void updateContentCaching(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void updateConnectionThrottle(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, StmRollBackException {
        if (loadBalancer.getConnectionLimit() != null) {
            ResourceTranslator translator = new ResourceTranslator();
            StingrayRestClient client = loadSTMRestClient(config);

            Protection protection;
            String protectionName = ZxtmNameBuilder.genVSName(loadBalancer);
            try {

                if (loadBalancer.hasSsl()) {
                    protectionName = ZxtmNameBuilder.genSslVSName(loadBalancer);
                    translator.translateProtectionResource(protectionName, loadBalancer);
                    protection = translator.getcProtection();
                    client.createProtection(protectionName, protection);

                } else {
                    translator.translateProtectionResource(protectionName, loadBalancer);
                    protection = translator.getcProtection();
                    client.createProtection(protectionName, protection);
                }
                LOG.info("Successfully created protection " + protectionName);

            } catch (StingrayRestClientException e) {
                LOG.error("Unexpected client error when creating connection throttle: " + protectionName);
            } catch (StingrayRestClientObjectNotFoundException onf) {
                LOG.error("Cannot create protection, Object not found: " + protectionName);
            }
        }

    }

    @Override
    public void deleteConnectionThrottle(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException, StmRollBackException {
        if (loadBalancer.getConnectionLimit() != null) {
            StingrayRestClient client = loadSTMRestClient(config);
            String protectionName = ZxtmNameBuilder.genVSName(loadBalancer);
            try {

                if (loadBalancer.hasSsl()) {
                    protectionName = ZxtmNameBuilder.genSslVSName(loadBalancer);
                    client.deleteProtection(protectionName);
                } else {
                    protectionName = ZxtmNameBuilder.genVSName(loadBalancer);
                    client.deleteProtection(protectionName);
                }
                LOG.info("Successfully deleted protection " + protectionName);
            } catch (StingrayRestClientException e) {
                LOG.error("Unexpected client error when deleting connection throttle: " + protectionName);

            } catch (StingrayRestClientObjectNotFoundException onf) {
                LOG.error("Cannot delete protection as client does not exist");
            }
        }
    }


    @Override
    public void updateAccessList(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteAccessList(LoadBalancerEndpointConfiguration config, Integer loadBalancerId, Integer accountId) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void suspendLoadBalancer(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void removeSuspension(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void createHostBackup(LoadBalancerEndpointConfiguration config, String backupName) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteHostBackup(LoadBalancerEndpointConfiguration config, String backupName) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void restoreHostBackup(LoadBalancerEndpointConfiguration config, String backupName) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setSubnetMappings(LoadBalancerEndpointConfiguration config, Hostssubnet hostssubnet) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteSubnetMappings(LoadBalancerEndpointConfiguration config, Hostssubnet hostssubnet) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Hostssubnet getSubnetMappings(LoadBalancerEndpointConfiguration config, String host) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getStatsSystemLoadBalancerNames(LoadBalancerEndpointConfiguration config) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Map<String, Integer> getLoadBalancerCurrentConnections(LoadBalancerEndpointConfiguration config, List<String> names) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Integer getLoadBalancerCurrentConnections(LoadBalancerEndpointConfiguration config, Integer accountId, Integer loadBalancerId, boolean isSsl) throws RemoteException, InsufficientRequestException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public int getTotalCurrentConnectionsForHost(LoadBalancerEndpointConfiguration config) throws RemoteException {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Stats getLoadBalancerStats(LoadBalancerEndpointConfiguration config, Integer loadbalancerId, Integer accountId) throws RemoteException, InsufficientRequestException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Map<String, Long> getLoadBalancerBytesIn(LoadBalancerEndpointConfiguration config, List<String> names) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getLoadBalancerBytesIn(LoadBalancerEndpointConfiguration config, Integer accountId, Integer loadBalancerId, boolean isSsl) throws RemoteException, InsufficientRequestException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Map<String, Long> getLoadBalancerBytesOut(LoadBalancerEndpointConfiguration config, List<String> names) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getLoadBalancerBytesOut(LoadBalancerEndpointConfiguration config, Integer accountId, Integer loadBalancerId, boolean isSsl) throws RemoteException, InsufficientRequestException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getHostBytesIn(LoadBalancerEndpointConfiguration config) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getHostBytesOut(LoadBalancerEndpointConfiguration config) throws RemoteException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean isEndPointWorking(LoadBalancerEndpointConfiguration config) throws RemoteException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer) throws RemoteException, InsufficientRequestException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, RateLimit rateLimit) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void updateRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, RateLimit rateLimit) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void removeAndSetDefaultErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws RemoteException, InsufficientRequestException, StmRollBackException {

        // This seems inefficient -- should rewrite to do this as one operation. TODO
        deleteErrorFile(config, loadBalancer);
        //setDefaultErrorFile(config, loadBalancer); //This isn't necessary anymore, since deleteErrorFile already ran an update
    }

    @Override
    public void setDefaultErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws InsufficientRequestException, RemoteException, StmRollBackException {
        setDefaultErrorFile(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer));
        if (loadBalancer.hasSsl()) {
            setDefaultErrorFile(config, loadBalancer, ZxtmNameBuilder.genSslVSName(loadBalancer));
        }
    }

    public void setDefaultErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName)
            throws InsufficientRequestException, RemoteException, StmRollBackException {
        // ** START Temporary for testing purposes
        StingrayRestClient client = null;
        if (config == null)
            client = getStingrayClient();
        else
            client = loadSTMRestClient(config);
        // ** END Temporary for testing purposes

        ResourceTranslator rt = new ResourceTranslator();
        rt.translateLoadBalancerResource(config, vsName, loadBalancer);
        VirtualServer vs = rt.getcVServer();
        LOG.debug(String.format("Attempting to set the default error file for %s", vsName));
        try {
            // Update client with new properties
            client.updateVirtualServer(vsName, vs);

            LOG.info(String.format("Successfully set the default error file for: %s", vsName));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            //not sure if this exception means what I thought it did
            LOG.warn(String.format("Virtual server %s does not exist, ignoring...", vsName));
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("There was a unexpected error setting the default error file for %s; Exception: %s", vsName, e.getMessage()));
        }
    }

    @Override
    public void uploadDefaultErrorFile(LoadBalancerEndpointConfiguration config, String content)
            throws RemoteException, InsufficientRequestException, StmRollBackException {

        // ** START Temporary for testing purposes
        StingrayRestClient client = null;
        if (config == null)
            client = getStingrayClient();
        else
            client = loadSTMRestClient(config);
        // ** END Temporary for testing purposes

        LOG.debug("Attempting to upload the default error file...");
        try {
            client.createExtraFile(Constants.DEFAULT_ERRORFILE, getFileWithContent(content));
            LOG.info("Successfully uploaded the default error file...");
        } catch (IOException e) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- IO exception", config.getEndpointUrl()));
        } catch (StingrayRestClientException ce) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- REST Client exception", config.getEndpointUrl()));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            LOG.error(String.format("Failed to upload default ErrorFile for %s -- Object not found", config.getEndpointUrl()));
        }
    }

    @Override
    public void deleteErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer)
            throws InsufficientRequestException, StmRollBackException {

        deleteErrorFile(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer));
        if (loadBalancer.hasSsl()) {
            deleteErrorFile(config, loadBalancer, ZxtmNameBuilder.genSslVSName(loadBalancer));
        }
    }

    public void deleteErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName)
            throws InsufficientRequestException, StmRollBackException {

        //TODO: a DELETE operation is a bit different then (CRU):D, in that we remove the 'item' or in this case 'errorpage'
        // TODO: from our 'DB', or entity loadBalancer object after we have verified it has been removed from the backend (STM).
        //ToDO: set NULL on the loadbalancer object before translation.

        // ** START Temporary for testing purposes
        StingrayRestClient client = null;
        if (config == null)
            client = getStingrayClient();
        else
            client = loadSTMRestClient(config);
        // ** END Temporary for testing purposes

        ResourceTranslator rt = new ResourceTranslator();
        rt.translateLoadBalancerResource(config, vsName, loadBalancer);
        VirtualServer vs = rt.getcVServer();
        String fileToDelete = getErrorFileName(vsName);
        try {
            LOG.debug(String.format("Attempting to delete a custom error file for %s (%s)", vsName, fileToDelete));

            // Update client with new properties
            client.updateVirtualServer(vsName, vs);

            // Delete the old error file
            client.deleteExtraFile(fileToDelete);

            LOG.info(String.format("Successfully deleted a custom error file for %s (%s)", vsName, fileToDelete));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            LOG.warn(String.format("Cannot delete custom error page as, %s, it does not exist. Ignoring...", fileToDelete));
        } catch (Exception ex) {
            LOG.error(String.format("There was a unexpected error deleting the error file for: %s Exception: %s", vsName, ex.getMessage()));
        }
    }

    @Override
    public void setErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String content) throws RemoteException, InsufficientRequestException, StmRollBackException {

        setErrorFile(config, loadBalancer, ZxtmNameBuilder.genVSName(loadBalancer), content);
        if (loadBalancer.hasSsl()) {
            setErrorFile(config, loadBalancer, ZxtmNameBuilder.genSslVSName(loadBalancer), content);
        }
    }

    public void setErrorFile(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName, String content) throws RemoteException, InsufficientRequestException, StmRollBackException {
        // ** START Temporary for testing purposes
        StingrayRestClient client = null;
        if (config == null)
            client = getStingrayClient();
        else
            client = loadSTMRestClient(config);
        // ** END Temporary for testing purposes

        ResourceTranslator rt = new ResourceTranslator();
        rt.translateVirtualServerResource(config, vsName, loadBalancer);
        VirtualServer vs = rt.getcVServer();
        String errorFileName = getErrorFileName(vsName);
        try {
            LOG.debug(String.format("Attempting to upload the error file for %s (%s)", vsName, errorFileName));
            client.createExtraFile(errorFileName, getFileWithContent(content));
            LOG.info(String.format("Successfully uploaded the error file for %s (%s)", vsName, errorFileName));
        } catch (IOException ioe) {
            // Failed to create file, use "Default"
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- IO exception", vsName, errorFileName));
            errorFileName = "Default";
        } catch (StingrayRestClientException ce) {
            // Failed to upload file, use "Default"
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- REST Client exception", vsName, errorFileName));
            errorFileName = "Default";
        } catch (StingrayRestClientObjectNotFoundException onf) {
            // Failed to create file, use "Default"
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- Object not found", vsName, errorFileName));
            errorFileName = "Default";
        }

        try {
            LOG.debug(String.format("Attempting to set the error file for %s (%s)", vsName, errorFileName));
            // Update client with new properties
            client.updateVirtualServer(vsName, vs);

            LOG.info(String.format("Successfully set the error file for %s (%s)", vsName, errorFileName));
        } catch (StingrayRestClientException ce) {
            // REST failure...
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- REST Client exception", vsName, errorFileName));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            // The file we uploaded wasn't there? Not good -- leave the object as it was before?
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- Object not found", vsName, errorFileName));
        }
    }

    private String getErrorFileName(String vsName) {
        String msg = String.format("%s_error.html", vsName);
        return msg;
    }

    private File getFileWithContent(String content) throws IOException {
        File file = File.createTempFile("StmAdapterImpl_", ".err");
        file.deleteOnExit();
        BufferedWriter out = new BufferedWriter(new FileWriter(file));
        out.write(content);
        out.close();
        return file;
    }

    @Override
    public void updateSslTermination(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, ZeusSslTermination sslTermination) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void removeSslTermination(LoadBalancerEndpointConfiguration config, LoadBalancer lb) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void enableDisableSslTermination(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, boolean isSslTermination) throws RemoteException, InsufficientRequestException, ZxtmRollBackException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setNodesPriorities(LoadBalancerEndpointConfiguration config, String poolName, LoadBalancer lb) throws RemoteException {
        //To change body of implemented methods use File | Settings | File Templates.
    }
}