/**
 * Copyright 2012-2013-2014. The SimGrid Team. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package.
 *
 * This class aims at controlling the interactions between the different components of the injector simulator.
 * It is mainly composed of static methods. Although it is rather ugly, this is the direct way to make a kind of
 * singleton ;)
 *
 * @author adrien.lebre@inria.fr
 * @contributor jsimao@cc.isel.ipl.pt
 */

package simulation;

import configuration.SimulatorProperties;
import configuration.VMClasses;
import configuration.XHost;
import configuration.XVM;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.trace.Trace;

import java.io.*;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: alebre
 * Date: 14/01/14
 * Time: 10:49
 * To change this template use File | Settings | File Templates.
 */
public class SimulatorManager {

    /**
     * The list of XVMs that are considered as off (i.e. the hosting machine is off)
     * @see configuration.XVM
     */
    private static LinkedList<XVM> sgVMsOff = null;
    /**
     * The list of XVMs that run
     * @see configuration.XVM
     */
    private static LinkedList<XVM> sgVMsOn = null;
    /**
     * The list of all XHosts
     * @see configuration.XHost
     */
    private static LinkedList<XHost> sgHostsOff= null;
    /**
     * The list of Xhosts  that are running
     */
    private static LinkedList<XHost> sgHostsOn= null;

    /**
     * Just a stupid boolean to stop the simulation when the injector is finishing to consume events from its queue
     */
    private static boolean endOfInjection = false;

    /**
     * When the injection is complete, we turn the endOfInjection boolean to true and kill the running daemon inside each VM
     */
	public static void setEndOfInjection(){
		endOfInjection=true;

        Msg.info(sgHostsOn.size()+"/"+getSGHosts().size()+"are up");
        Msg.info(sgVMsOn.size()+"/"+getSGVMs().size()+" are up");

        for (XHost host : SimulatorManager.getSGHosts()) {
            Msg.info(host.getName() + " has been turned off "+host.getTurnOffNb()+" times and violated "+host.getNbOfViolations());
        }

        // Kill all VMs in order to finalize the simulation correctly
        for (XVM vm : SimulatorManager.getSGVMs()) {
            Msg.info(vm.getName() + " load changes: "+vm.getNbOfLoadChanges());
            vm.getDaemon().kill();
        }
	}

    /**
     * @return whether the injection is completed or not
     */
	public static boolean isEndOfInjection(){
		return endOfInjection;
	}


    /**
     * @return the collection of XVMs: all VMs, the running and the ones that are considered as dead
     * (i.e. hosted on hosts that have been turned off)
     */
    public static Collection<XVM> getSGVMs(){
        LinkedList<XVM> tmp = new LinkedList<XVM>(sgVMsOn);
        tmp.addAll(sgVMsOff);
        return tmp;
    }

    /**
     * @return the collection of running XVMs
     */
    public static Collection<XVM> getSGVMsOn(){
        return sgVMsOn;
    }

    /**
     * @return the collection of the XVMs considered as dead
     */
    public static Collection<XVM> getSGVMsOff(){
        return sgVMsOff;
    }

    /**
     * @return the collection of XHosts (i.e. the Hosts composing the infrastructures)
     * Please note that the service node (i.e. the host on wich the injector is running) is not included in the return collection.
     * The service node is not extended as an XHost. If you need to retrieve the MSG Host instance corresponding to the service node,
     * you should invoke the msg Host.getByName(SimulatorManager.getServiceNodeName()).
     */
    public static Collection<XHost> getSGHosts(){
        LinkedList<XHost> tmp = new LinkedList<XHost>(sgHostsOn);
        tmp.addAll(sgHostsOff);
        return tmp;
    }

    /**
     * @return the collection of XHosts (i.e. the Hosts composing the infrastructures)
     * Please note that the service node (i.e. the host on wich the injector is running) is not included in the return collection.
     * The service node is not extended as an XHost. If you need to retrieve the MSG Host instance corresponding to the service node,
     * you should invoke the msg Host.getByName(SimulatorManager.getServiceNodeName()).
     */
    public static Collection<XHost> getSGHostsOn(){
       return sgHostsOn;
    }


    /**
     * @return the collection of XHosts that are currently off
     */
    public static Collection<XHost> getSGHostsOff(){
        return sgHostsOff;
    }



    /**
     * @return the name of the service node (generally node0, if you do not change the first part of the main regarding the generation
     * of the deployment file).
     * If you change it, please note that you should then update the getServiceNodeName code.
     */
    public static String getServiceNodeName() {
        return "node0";
    }

    /**
     * For each MSG host (but the service node), the function creates an associated XHost.
     * As a reminder, the XHost class extends the Host one by aggregation.
     * At the end, all created hosts have been inserted into the sgHosts collection (see getSGHosts function)
     * @param nbOfHosts the number of hosts that has been created by SimGrid (e.g. 10 = 1 service node + 9 hosting nodes)
     */
    public static void initHosts(int nbOfHosts){
        // Since SG does not make any distinction between Host and Virtual Host (VMs and Hosts belong to the Host SG table)
        // we should retrieve first the real host in a separated table
        // Please remind that node0 does not host VMs (it is a service node) and hence, it is managed separately (getServiceNodeName())
        sgHostsOn = new LinkedList<XHost>();
        sgHostsOff = new LinkedList<XHost>();
        XHost xtmp;
        for(int i = 1 ; i <= nbOfHosts  ; i ++){
            try {
                Host tmp = Host.getByName("node" + i);
                // The SimulatorProperties.getCPUCapacity returns the value indicated by nodes.cpucapacity in the simulator.properties file
                xtmp = new XHost (tmp, SimulatorProperties.getMemoryTotal(), SimulatorProperties.getNbOfCPUs(), SimulatorProperties.getCPUCapacity(), SimulatorProperties.getNetCapacity(), "127.0.0.1");
                xtmp.turnOn();
               sgHostsOn.add(xtmp);
            } catch (HostNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Create and assign the VMs on the different hosts.
     * For the moment, the initial placement follows a simple round robin strategy
     * The algorithm fill the first host with the n first VMs until it reaches either the memory limit, then it switches to the second host and so on.
     * Note that if the ''balance'' mode is enabled then the initial placement will put the same number of VMs on each node.
     * The function can crash if there are two many VMs for the physical resources.
     * At the end the collection SimulatorManager.getSGVMs() is filled.
     * @param nbOfHosts the number of the hosts composing the infrastructure
     * @param nbOfVMs the number of the VMs to instanciate
     */
    public static void instanciateVMs(int nbOfHosts, int nbOfVMs, boolean balance) {

        int nodeIndex = 1; // 0 corresponds to the service node
        int[] nodeMemCons = new int[nbOfHosts+1]; // +1 since Node0 is used for the injector and does not host any vm.
        int vmIndex= 0;
        int nbVMOnNode;
        Random r = new Random(SimulatorProperties.getSeed());
        int nbOfVMClasses = VMClasses.CLASSES.size();
        VMClasses.VMClass vmClass;

        initHosts(nbOfHosts);
        sgVMsOn = new LinkedList<XVM>();
        sgVMsOff = new LinkedList<XVM>();

        XVM sgVMTmp;

        Iterator<XHost> sgHostsIterator = SimulatorManager.getSGHosts().iterator();

        XHost sgHostTmp = sgHostsIterator.next();
        nodeMemCons[nodeIndex]=0;
        nbVMOnNode =0;

        //Add VMs to each node, preventing memory over provisioning
        while(vmIndex < nbOfVMs){

            // Select the class for the VM
            vmClass = VMClasses.CLASSES.get(r.nextInt(nbOfVMClasses));

            //Check whether we can put this VM on the current node if not get the next one
            //The first condition controls the memory over provisioning issue while the second one enables to switch to
            // the next node if the ''balance'' mode is enabled.
            // If there is no more nodes, then we got an exception and the simulator.properties should be modified.
            try {
                while ((nodeMemCons[nodeIndex] + vmClass.getMemSize() > sgHostTmp.getMemSize()) || (balance && nbVMOnNode >= nbOfVMs/nbOfHosts)) {
                    sgHostTmp = sgHostsIterator.next();
                    nodeMemCons[++nodeIndex] = 0;
                    nbVMOnNode = 0;
                }
            } catch(NoSuchElementException ex){
                System.err.println("There is not enough memory on the physical hosts to start all VMs");
                System.err.println("(Please fix simulator.properties parameters and you should dive in the SimulatorManager.instanciateVMs() function");
                System.exit(1);
            }

            // Creation of the VM
            sgVMTmp = new XVM(sgHostTmp, "vm-" + vmIndex,
                        vmClass.getNbOfCPUs(), vmClass.getMemSize(), vmClass.getNetBW(), null, -1, vmClass.getMigNetBW(), vmClass.getMemIntensity());
            sgVMsOn.add(sgVMTmp);
            vmIndex++;

            Msg.info(String.format("vm: %s, %d, %d, %s",
                        sgVMTmp.getName(),
                        vmClass.getMemSize(),
                        vmClass.getNbOfCPUs(),
                        "NO IPs defined"
            ));
            Msg.info("vm " + sgVMTmp.getName() + " is " + vmClass.getName() + ", dp is " + vmClass.getMemIntensity());

            // Assign the new VM to the current host.
            sgHostTmp.start(sgVMTmp);     // When the VM starts, its getCPUDemand equals 0
            nbVMOnNode ++;
            nodeMemCons[nodeIndex] += sgVMTmp.getMemSize();
        }
    }

    /**
     * write the current configuration in the ''logs/simulatorManager/'' directory
     */
    public static void writeCurrentConfiguration(){
        try {
            File file = new File("logs/simulatorManager/conf-"+ System.currentTimeMillis() + ".txt");
            file.getParentFile().mkdirs();
            BufferedWriter bw = new BufferedWriter(new FileWriter(file));
            for (XHost h: SimulatorManager.getSGHosts()){
                bw.write(h.getName()+":");
                for (XVM vm: h.getRunnings()){
                    bw.write(" "+vm.getName());
                }
                bw.write("\n");
                bw.flush();
            }

            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Remove all logs from the previous run
     */
    public static void cleanLog(){
        try {
            Runtime.getRuntime().exec("rm -rf ./logs/*");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return whether the current placement is viable or not (i.e. if every VM gets its expectations).
     * Please note that we are considering only the host that are running.
     */
    public static boolean isViable() {
        for (XHost h: sgHostsOn){
                  if(!h.isViable())
                return false;
        }
        return true;
    }

    /**
     * @return the average expected load at a particular moment (i.e. average load of each node)
     * Please note that we are considering only the host that are running.
     */
    public static double getCPUDemand() {
        double cons=0;
        double tmpLoad = 0 ;
        for(XHost h: sgHostsOn){
            tmpLoad = h.getCPUDemand()*100/h.getCPUCapacity();
            cons+= tmpLoad ;
        }
        return cons/sgHostsOn.size();
    }

    /**
     * @return the number of hosts that are active (i.e. that host at least one VM)
     */
    public static int getNbOfUsedHosts() {
        int i=0;
        for (XHost h: sgHostsOn){
            if(h.getNbVMs()>0)
                i++;
        }
        return i;
    }

    /**
     * Return the XHost entitled ''name'', if not return null (please note that the search is performed by considering
     * all hosts (i.e. event the off ones)
     * @param name the name of the host requested
     * @return the corresponding XHost instance (null if there is no corresponding host in the sgHosts collection)
     */
    public static XHost getXHostByName(String name) {
        for (XHost tmp:SimulatorManager.getSGHosts()) {
            if(tmp.getName().equals(name))
                return tmp;
        }
        return null;
    }

    /**
     * Return the XVM entitled ''name'', if not return null please note that the search is performed by considering
     * all VMs (i.e. event the off ones)
     * @param name the name of the vm requested
     * @return the corresponding XVM instance (null if there is no corresponding vm in the sgVMs collection)
     */
    public static XVM getXVMByName(String name) {
        for (XVM tmp:SimulatorManager.getSGVMs()) {
            if(tmp.getName().equals(name))
                return tmp;
        }
        return null;
    }

    /**
     * Change the load of a VM.
     * Please note that we do not detect violations on off hosts (i.e. if the nodes that hosts the VM is off, we change
     * the load of the vm for consistency reasons but we do not consider the violation that may result from this change).
     * @param sgVM the VM that should be updated
     * @param load the new expected load
     */
    public static void updateVM(XVM sgVM, int load) {
        XHost tmpHost = sgVM.getLocation();
        boolean previouslyViable = tmpHost.isViable();
        sgVM.setLoad(load);

        // If the node is off, we change the VM load but we do not consider it for possible violation and do not update
        // neither the global load of the node nor the global load of the cluster.
        // Violations are detected only on running node
        if (!tmpHost.isOff()){

            Msg.info("Current getCPUDemand "+SimulatorManager.getCPUDemand()+"\n");


            if(previouslyViable) {
                if (!tmpHost.isViable()) {
                    Msg.info("STARTING VIOLATION ON "+tmpHost.getName()+"\n");
                    tmpHost.incViolation();
                    Trace.hostSetState(tmpHost.getName(), "PM", "violation");
                }
                else if(!previouslyViable){
                    if (tmpHost.isViable()) {
                        Msg.info("ENDING VIOLATION ON "+tmpHost.getName()+"\n");
                        Trace.hostSetState (tmpHost.getName(), "PM", "normal");
                    }
                }


                // Update getCPUDemand of the host
                Trace.hostVariableSet(tmpHost.getName(), "LOAD", tmpHost.getCPUDemand());

                //Update global getCPUDemand
                Trace.hostVariableSet(SimulatorManager.getServiceNodeName(),  "LOAD", SimulatorManager.getCPUDemand());
            }
        }
    }

    /**
     * Turn on the XHost host
     * @param host the host to turn on
     */
    public static void turnOn(XHost host) {
        if(host.isOff()) {
            Msg.info("Turn on node "+host.getName());
            host.turnOn();
            sgHostsOff.remove(host);
            sgHostsOn.add(host);

            for (XVM vm: host.getRunnings()){
                sgVMsOff.remove(vm);
                sgVMsOn.add(vm);
            }

            // Update getCPUDemand of the host
            Trace.hostVariableSet(host.getName(), "LOAD", host.getCPUDemand());

            //Update global getCPUDemand
            Trace.hostVariableSet(SimulatorManager.getServiceNodeName(),  "LOAD", SimulatorManager.getCPUDemand());

        } else{
            Msg.info("Weird... you are asking to turn on a host that is already on !");
        }
    }

    /**
     * Turn off the XHost host
     * @param host the host to turn off
     */
    public static void turnOff(XHost host) {

        if(!host.isOff()) {
            Msg.info("Turn off node "+host.getName());
            // First remove all VMs hosted on the node from the global collection
            // The VMs are still referenced on the node
            for (XVM vm : host.getRunnings()) {
                sgVMsOn.remove(vm);
                sgVMsOff.add(vm);
            }

            // Second, remove the node from the collection of running host and add it to the collection of off ones
            sgHostsOn.remove(host);
            sgHostsOff.add(host);

            // Update getCPUDemand of the host
            Trace.hostVariableSet(host.getName(), "LOAD", 0);

            //Update global getCPUDemand
            Trace.hostVariableSet(SimulatorManager.getServiceNodeName(),  "LOAD", SimulatorManager.getCPUDemand());

            // Finally turn the node off
            host.turnOff();

        }
        else{
            Msg.info("Weird... you are asking to turn off a host that is already off !");
        }
    }
}
