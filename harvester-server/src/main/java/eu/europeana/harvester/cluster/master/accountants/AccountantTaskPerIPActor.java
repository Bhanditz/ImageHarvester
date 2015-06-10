package eu.europeana.harvester.cluster.master.accountants;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import eu.europeana.harvester.cluster.domain.TaskState;
import eu.europeana.harvester.cluster.domain.messages.inner.*;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AccountantTaskPerIPActor extends UntypedActor {

    private final LoggingAdapter LOG = Logging.getLogger(getContext().system(), this);


    /**
     * Map which stores a list of tasks for each IP address.
     */
    private final Map<String, List<String>> tasksPerIP = new HashMap<>();

    @Override
    public void onReceive(Object message) throws Exception {
        try {
            if (message instanceof CheckIPsWithJobs) {
                final Double percentage = checkIPsWithJobs(((CheckIPsWithJobs) message).getIpsWithJobs());
                getSender().tell(percentage, getSelf());
                return;
            }

            if (message instanceof GetTasksFromIP) {
                final String IP = ((GetTasksFromIP) message).getIP();
                List<String> tasks = tasksPerIP.get(IP);
                if(tasks == null) {
                    tasks = new ArrayList<>();
                }

                getSender().tell(tasks, getSelf());
                return;
            }

            if (message instanceof AddTasksToIP) {
                final String IP = ((AddTasksToIP) message).getIP();
                final List<String> taskIDs = ((AddTasksToIP) message).getTasks();

                tasksPerIP.put(IP, taskIDs);
                return;
            }

            if (message instanceof RemoveTaskFromIP) {
                final String taskID = ((RemoveTaskFromIP) message).getTaskID();
                final String IP = ((RemoveTaskFromIP) message).getIP();

                if(!tasksPerIP.containsKey(IP)) {return;}
                final List<String> taskFromIP = tasksPerIP.get(IP);
                taskFromIP.remove(taskID);

                tasksPerIP.put(IP, taskFromIP);
                return;
            }

            if (message instanceof GetOverLoadedIPs) {
                final int threshold = ((GetOverLoadedIPs) message).getThreshold();
                getSender().tell( getIPsWithTooManyTasks(threshold), ActorRef.noSender());
                return;

            }

            if (message instanceof Monitor) {
                monitor();
                return;
            }

            if (message instanceof CleanIPs) {
                ArrayList<String> ips = ((CleanIPs) message).getIPs();
                cleanIPs(ips);

                return;
            }

            if (message instanceof Clean) {
                LOG.info("Clean maps Tasks/IP accountant actor");
                tasksPerIP.clear();

                return;
            }


        } catch(Exception e) {

            LOG.error("Tasks/IP Accountant actor: {}", e.getMessage());
        }
    }


    private void cleanIPs(ArrayList<String> IPsToCheck) {
        for (final Map.Entry<String, List<String>> task : tasksPerIP.entrySet()) {
            if ( IPsToCheck.contains(task.getKey()) && !checkTaskStates(task.getValue())) {
                String IP = task.getKey();
                tasksPerIP.remove(IP);
                LOG.info("Removed " + IP);
            }
        }
        return;
    }



    private Double checkIPsWithJobs(HashMap<String, Boolean> ipsWithJobs) {

        final int nrOfIPs = tasksPerIP.size();
        if (nrOfIPs == 0) {return 0.0;}

        int ipsWithoutOngoingRequest = 0;
        if(ipsWithJobs == null) {return 0.0;}

        for (final String IP : ipsWithJobs.keySet()) {
            final List<String> taskIDs = tasksPerIP.get(IP);
            if(taskIDs == null) {continue;}
            if(!checkTaskStates(taskIDs)) ipsWithoutOngoingRequest++;
        }

        return  100.0 - (100.0 * ipsWithoutOngoingRequest / nrOfIPs);
    }



    private void monitor() {
        LOG.info("Nr of tasks per ip: ");
        int ipsWithoutLoadedTasks = 0;
        int ipsDownloading = 0;
        int ipsProcessing = 0;

        for (final Map.Entry<String, List<String>> task : tasksPerIP.entrySet()) {
            int ready = 0;
            int downloading = 0;
            int processing = 0;

            for (final String taskID : task.getValue()) {

                final TaskState taskState = getTaskState(taskID);

                if (taskState.equals(TaskState.READY)) {
                    ready += 1;
                }
                if (taskState.equals(TaskState.DOWNLOADING)) {
                    downloading += 1;
                }
                if (taskState.equals(TaskState.PROCESSING)) {
                    processing += 1;
                }
            }

            if (ready == 0) {
                ipsWithoutLoadedTasks += 1;
            }
            if (downloading != 0) {
                ipsDownloading += 1;
            }
            if (processing != 0) {
                ipsProcessing += 1;
            }

            LOG.info("{} : ready: {}, downloading: {}, processing: {}", task.getKey(), ready, downloading, processing);
        }
        LOG.info("IPS without loaded tasks: {}", ipsWithoutLoadedTasks);
        LOG.info("IPS downloading tasks: {}", ipsDownloading);
        LOG.info("IPS processing tasks: {}", ipsProcessing);

        LOG.info("Number of IPs: {}", tasksPerIP.size());
        //LOG.info("Number of loaded tasks: {}", allTasks.size());
    }



    private ArrayList<String> getIPsWithTooManyTasks( int threshold ) {

        ArrayList<String> IPs = new ArrayList<>();

        for (final Map.Entry<String, List<String>> task : tasksPerIP.entrySet()) {
            int ready = 0;

            for (final String taskID : task.getValue()) {

                final TaskState taskState = getTaskState(taskID);

                if (taskState.equals(TaskState.READY))
                    ready++;
            }

            if (ready > threshold ) {
                IPs.add(task.getKey());
            }


        }
        LOG.info("Found {} IPs that are overloaded", IPs.size());
        return IPs;
    }



    private boolean checkTaskStates ( List<String> taskIDs) {

        boolean foundActive = false;

        for (final String taskID : taskIDs) {

            final Timeout timeout = new Timeout(Duration.create(10, TimeUnit.SECONDS));
            final ActorRef accountantAll = getContext().actorFor("../accountantAll");

            final Future<Object> future = Patterns.ask(accountantAll, new GetTaskState(taskID), timeout);
            TaskState taskState = null;
            try {
                taskState = (TaskState) Await.result(future, timeout.duration());
            } catch (Exception e) {
                LOG.error("Error: {}", e);
            }

//            if ((taskState!=null) && (taskState.equals(TaskState.READY)||taskState.equals(TaskState.DOWNLOADING) || taskState.equals(TaskState.PROCESSING)) ) {
            if ((taskState!=null) && (taskState.equals(TaskState.READY)||taskState.equals(TaskState.DOWNLOADING)) ) {
                foundActive = true;
                break;
            }
        }

        return foundActive;


    }

    private TaskState getTaskState ( String taskID) {


            final Timeout timeout = new Timeout(Duration.create(10, TimeUnit.SECONDS));
            final ActorRef accountantAll = getContext().actorFor("../accountantAll");

            final Future<Object> future = Patterns.ask(accountantAll, new GetTaskState(taskID), timeout);
            TaskState taskState = null;
            try {
                taskState = (TaskState) Await.result(future, timeout.duration());
            } catch (Exception e) {
                LOG.error("Error: {}", e);
            }

            return taskState;


    }

}