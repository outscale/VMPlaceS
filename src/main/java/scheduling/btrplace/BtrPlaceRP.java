package scheduling.btrplace;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.btrplace.model.DefaultModel;
import org.btrplace.model.Mapping;
import org.btrplace.model.Model;
import org.btrplace.model.VM;
import org.btrplace.model.Node;
import org.btrplace.model.view.ShareableResource;
import org.btrplace.plan.ReconfigurationPlan;
import org.btrplace.scheduler.SchedulerException;
import org.btrplace.scheduler.choco.ChocoScheduler;
import org.btrplace.scheduler.choco.DefaultChocoScheduler;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Msg;
import scheduling.Scheduler;
import scheduling.SchedulerRes;
import trace.Trace;

import java.util.*;

/**
 * @author Adrian Fraisse
 *
 * Implementation of the Scheduler interface using the BtrPlace API
 */
public class BtrPlaceRP implements Scheduler {

    // TODO Killian refactor this into an Abstract class
    /**
     * The time spent to compute VMPP
     *  @deprecated Please consider that this value is currently deprecated and will be set to zero untill it will be fixed - Adrien, Nov 18 2011
     */
    protected long timeToComputeVMPP;

    //The time spent to compute VMRP
    protected long timeToComputeVMRP;

    //The time spent to apply the reconfiguration plan
    protected long timeToApplyReconfigurationPlan;

    //The cost of the reconfiguration plan
    protected int reconfigurationPlanCost;

    /**
     * Indicates if the reconfiguration plan has been aborted
     */
    public boolean isRPAborted;

    /**
     * The initial configuration
     */
    public Model sourceModel;

    /**
     * The resulting configuration
     */
    public Model destModel;

    /**
     * The computed reconfiguration plan
     */
    public ReconfigurationPlan reconfigurationPlan;

    /**
     * Map to link BtrPlace nodes ids to XHosts
     */
    private Map<Integer, String> nodesMap;

    /**
     * Map to link BtrPlace vm ids to XVMs
     */
    private Map<Integer, String> vmMap;

    /**
     * The BtrPlace scheduler
     */
    private ChocoScheduler btrSolver;
    private int loopID;

    public BtrPlaceRP(Collection<XHost> xHosts, int loopID) {
        this.loopID = loopID;
        this.btrSolver = new DefaultChocoScheduler();
        this.nodesMap = new HashMap<>();
        this.vmMap = new HashMap<>();
        this.sourceModel = this.extractModel(xHosts);

    }

    public BtrPlaceRP(Collection<XHost> xHosts) {
        this(xHosts, new Random().nextInt());
    }

    /**
     * Creates a Model for BtrPlace
     * @param xHosts Collection of Xhosts declared as hosting nodes and that are turned on
     * @return A model representing the infrastructure
     */
    public Model extractModel(Collection<XHost> xHosts) {

        Model model = new DefaultModel();
        Mapping mapping = model.getMapping();

        // Creation of a view for defining CPU & Memory resources
        ShareableResource rcCPU = new ShareableResource("cpu", SimulatorProperties.DEFAULT_CPU_CAPACITY, 0);
        ShareableResource rcMem = new ShareableResource("mem", SimulatorProperties.DEFAULT_MEMORY_TOTAL, 0);

        // Add nodes
        for (XHost tmpH : xHosts) {
            // Consider only hosts that are turned on
            if (tmpH.isOff()) {
                System.err.println("WTF, you are asking me to analyze a dead node (" + tmpH.getName() + ")");
            }

            // Creates a physical node
            Node n = model.newNode();
            this.nodesMap.put(n.id(), tmpH.getName());

            // Ajout de la machine physique au mapping
            mapping.addOnlineNode(n);

            // Node's resources are explicitly set
            rcCPU.setCapacity(n, tmpH.getCPUCapacity());
            rcMem.setCapacity(n, tmpH.getMemSize());


            // Declare running VMs mapping
            for (XVM tmpVM : tmpH.getRunnings()) {
                VM v = model.newVM();
                mapping.addRunningVM(v, n);
                this.vmMap.put(v.id(), tmpVM.getName());
                rcCPU.setConsumption(v, (int) tmpVM.getCPUDemand());
                rcMem.setConsumption(v, tmpVM.getMemSize());
            }

        }

        model.attach(rcCPU);
        model.attach(rcMem);

        return model;
    }

    public ComputingState computeReconfigurationPlan() {
        ComputingState res = ComputingState.SUCCESS;

        try {
            timeToComputeVMRP = System.currentTimeMillis();
            // As for now, constraints are not implemented - Adrian, Nov 5 2015
            reconfigurationPlan = this.btrSolver.solve(sourceModel, null);
            timeToComputeVMRP = System.currentTimeMillis() - timeToComputeVMRP;
        } catch (SchedulerException e) {
            e.printStackTrace();
            res = ComputingState.RECONFIGURATION_FAILED ;
            timeToComputeVMRP = System.currentTimeMillis() - timeToComputeVMRP;
            reconfigurationPlan = null;
        }

        if(reconfigurationPlan != null){
            if(reconfigurationPlan.getActions().isEmpty())
                res = ComputingState.NO_RECONFIGURATION_NEEDED;
            if (!reconfigurationPlan.isApplyable())
                res = ComputingState.RECONFIGURATION_FAILED;

            reconfigurationPlanCost = reconfigurationPlan.getDuration();
            destModel = reconfigurationPlan.getResult();

            // TODO Adrian : Compute graphDepth & nbMigrations - if it's meaningfull
        }

        return res;
    }


    /**
     * @param hostsToCheck
     * @return the duration of the reconfiguration (i.e. > 0), -1 there is no viable reconfiguration, -2 the reconfiguration crash
     */
    public SchedulerRes checkAndReconfigure(Collection<XHost> hostsToCheck) {

        long beginTimeOfCompute;
        long endTimeOfCompute;
        long computationTime;
        ComputingState computingState;
        long reconfigurationTime;
        SchedulerRes enRes = new SchedulerRes();

		/* Tracing code */
        for (XHost h : hostsToCheck) {
            if (!h.isViable())
                Trace.hostPushState(h.getName(), "PM", "violation-det");
            Trace.hostSetState(h.getName(), "SERVICE", "booked");
        }

        Msg.info("Launching scheduler (loopId = " + loopID + ") - start to compute");
        Msg.info("Nodes considered: "+ sourceModel.getMapping().getAllNodes().toString());

        /** PLEASE NOTE THAT ALL COMPUTATIONS BELOW DOES NOT MOVE FORWARD THE MSG CLOCK ***/
        beginTimeOfCompute = System.currentTimeMillis();
        computingState = this.computeReconfigurationPlan();
        endTimeOfCompute = System.currentTimeMillis();
        computationTime = (endTimeOfCompute - beginTimeOfCompute);

        /* Tracing code */
        double computationTimeAsDouble = ((double) computationTime) / 1000;

        int migrationCount = 0;
        if (computingState.equals(ComputingState.SUCCESS)) {
            migrationCount = this.reconfigurationPlan.getSize();
        }

        int partitionSize = hostsToCheck.size();

        /** **** NOW LET'S GO BACK TO THE SIMGRID WORLD **** */

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "compute", String.format("{\"duration\" : %f, \"result\" : \"%s\", \"migration_count\": %d, \"psize\": %d}", computationTimeAsDouble, computingState, migrationCount, partitionSize));


        try {
            org.simgrid.msg.Process.sleep(computationTime); // instead of waitFor that takes into account only seconds
        } catch (HostFailureException e) {
            e.printStackTrace();
        }

        Msg.info("Computation time (in ms):" + computationTime);
        enRes.setDuration(computationTime);

        if (computingState.equals(ComputingState.NO_RECONFIGURATION_NEEDED)) {
            Msg.info("Configuration remains unchanged"); //res is already set to 0.
        } else if (computingState.equals(ComputingState.SUCCESS)) {

			/* Tracing code */
            // TODO Adrien -> Adrien, try to consider only the nodes that are impacted by the reconfiguration plan
            for (XHost h : hostsToCheck)
                Trace.hostSetState(h.getName(), "SERVICE", "reconfigure");

            Trace.hostPushState(Host.currentHost().getName(), "SERVICE", "reconfigure");

            // Applying reconfiguration plan
            Msg.info("Starting reconfiguration");
            double startReconfigurationTime = Msg.getClock();
            this.applyReconfigurationPlan();
            double endReconfigurationTime = Msg.getClock();
            reconfigurationTime = ((long) (endReconfigurationTime - startReconfigurationTime) * 1000);
            Msg.info("Reconfiguration time (in ms): " + reconfigurationTime);
            enRes.setDuration(enRes.getDuration() + reconfigurationTime);
            Msg.info("Number of nodes used: " + hostsToCheck.size());

            if (isRPAborted)
                enRes.setRes(-2);
            else
                enRes.setRes(1);

            Trace.hostPopState(Host.currentHost().getName(), "SERVICE"); //PoP reconfigure;
        } else {
            Msg.info("Entropy did not find any viable solution");
            enRes.setRes(-1);
        }

		/* Tracing code */
        for (XHost h : hostsToCheck)
            Trace.hostSetState(h.getName(), "SERVICE", "free");

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "free");
        return enRes;
    }

    public void applyReconfigurationPlan() {
        // TODO - Use the dependency applier
    }

}