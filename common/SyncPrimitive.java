package common;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

public class SyncPrimitive implements Watcher {

    static ZooKeeper zk = null;
    static final Integer mutex = -1; // Use a final object for locking

    String root;

    SyncPrimitive(String address) {
        if(zk == null){
            try {
                System.out.println("Starting ZK:");
                zk = new ZooKeeper(address, 3000, this);
                System.out.println("Finished starting ZK: " + zk);
            } catch (IOException e) {
                System.out.println(e.toString());
                zk = null;
            }
        }
    }

    @Override
    synchronized public void process(WatchedEvent event) {
        synchronized (mutex) {
            // A generic notification for any waiting threads
            mutex.notifyAll();
        }
    }

    /**
     * Barrier: A distributed barrier implementation.
     * All processes calling enter() will wait until the specified number of processes have joined.
     * Then, all will be allowed to proceed.
     */
    static public class Barrier extends SyncPrimitive {
        int size;
        String name;

        public Barrier(String address, String root, int size) {
            super(address);
            this.root = root;
            this.size = size;

            if (zk != null) {
                try {
                    ensurePathExists(root);
                } catch (KeeperException | InterruptedException e) {
                    System.out.println("Keeper exception when instantiating barrier: " + e.toString());
                }
            }

            try {
                name = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                System.out.println(e.toString());
            }
        }

        public boolean enter() throws KeeperException, InterruptedException{
            zk.create(root + "/" + name, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            while (true) {
                synchronized (mutex) {
                    List<String> list = zk.getChildren(root, true);
                    if (list.size() < size) {
                        mutex.wait(); // Wait for a notification on the root node
                    } else {
                        return true;
                    }
                }
            }
        }

        boolean leave() throws KeeperException, InterruptedException{
            while (true) {
                synchronized (mutex) {
                    List<String> list = zk.getChildren(root, true);
                    if (list.size() > 0) {
                        mutex.wait();
                    } else {
                        return true;
                    }
                }
            }
        }
    }

    /**
     * Producer-Consumer Queue
     */
    static public class Queue extends SyncPrimitive {
        public Queue(String address, String name) {
            super(address);
            this.root = name;
            if (zk != null) {
                try {
                    ensurePathExists(root);
                } catch (KeeperException | InterruptedException e) {
                    System.out.println("Keeper exception when instantiating queue: " + e.toString());
                }
            }
        }

        boolean produce(int i) throws KeeperException, InterruptedException {
            ByteBuffer b = ByteBuffer.allocate(4);
            b.putInt(i);
            byte[] value = b.array();
            return produce(value);
        }

        public boolean produce(byte[] value) throws KeeperException, InterruptedException {
            zk.create(root + "/element", value, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            return true;
        }

        public byte[] consumeBytes() throws KeeperException, InterruptedException {
            while (true) {
                synchronized (mutex) {
                    List<String> list = zk.getChildren(root, true); // Set a watch
                    if (list.isEmpty()) {
                        return null;
                    } else {
                        Collections.sort(list); // Sort to find the oldest element
                        String minString = list.get(0);
                        try {
                            byte[] data = zk.getData(root + "/" + minString, false, null);
                            zk.delete(root + "/" + minString, -1);
                            return data;
                        } catch (KeeperException.NoNodeException e) {
                            // Another consumer got it first, just retry.
                        }
                    }
                }
            }
        }

        int consume() throws KeeperException, InterruptedException{
            byte[] data = consumeBytes();
            if (data == null) return -1;
            ByteBuffer buffer = ByteBuffer.wrap(data);
            return buffer.getInt();
        }
    }

    /**
     * Distributed Lock: Provides exclusive access to a resource.
     */
    static public class Lock extends SyncPrimitive {
        private String pathName;

        public Lock(String address, String name) {
            super(address);
            this.root = name;
            if (zk != null) {
                try {
                    ensurePathExists(root);
                } catch (KeeperException | InterruptedException e) {
                    System.out.println("Keeper exception when instantiating lock: " + e.toString());
                }
            }
        }

        /**
         * Blocks until the lock is acquired.
         * @return true when the lock is acquired.
         */
        public boolean lock() throws KeeperException, InterruptedException {
            pathName = zk.create(root + "/lock-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            System.out.println("My lock path is: " + pathName);
            return testMin();
        }

        /**
         * Releases the lock.
         */
        public void unlock() throws KeeperException, InterruptedException {
            if (this.pathName != null) {
                zk.delete(this.pathName, -1);
                this.pathName = null;
            }
        }
        
        private boolean testMin() throws KeeperException, InterruptedException {
            while (true) {
                synchronized (mutex) {
                    List<String> children = zk.getChildren(root, false);
                    Collections.sort(children);
                    String myNodeName = pathName.substring(root.length() + 1);

                    if (myNodeName.equals(children.get(0))) {
                        System.out.println("Lock acquired for " + myNodeName + "!");
                        return true;
                    }

                    int myIndex = children.indexOf(myNodeName);
                    String nodeToWatch = children.get(myIndex - 1);

                    System.out.println("Watching " + root + "/" + nodeToWatch);
                    Stat s = zk.exists(root + "/" + nodeToWatch, true); // Set watch

                    if (s != null) {
                        mutex.wait(); // Wait for notification
                    }
                    // If s is null, the node to watch was deleted. Loop again to re-check.
                }
            }
        }

        @Override
        synchronized public void process(WatchedEvent event) {
            synchronized (mutex) {
                if (event.getType() == Event.EventType.NodeDeleted) {
                    // A node was deleted, wake up the waiting thread in testMin()
                    mutex.notifyAll();
                }
            }
        }
    }

    /**
     * Leader Election: Elects a single leader from a group of nodes.
     */
    static public class Leader extends SyncPrimitive {
        String id;
        String pathName;
        String leaderNodePath;

        public Leader(String address, String electionPath, String leaderNode, int id) {
            super(address);
            this.root = electionPath;
            this.leaderNodePath = leaderNode;
            this.id = Integer.toString(id);
            if (zk != null) {
                try {
                    ensurePathExists(root);
                } catch (KeeperException | InterruptedException e) {
                    System.out.println("Keeper exception when instantiating leader election: " + e.toString());
                }
            }
        }
        
        /**
         * Enters the election and blocks until this node becomes the leader.
         */
        public void elect() throws KeeperException, InterruptedException {
            pathName = zk.create(root + "/n-", id.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            System.out.println("My path name is: " + pathName + " and my id is: " + id + "!");
            check();
        }
        
        private void check() throws KeeperException, InterruptedException {
            while (true) {
                synchronized (mutex) {
                    List<String> children = zk.getChildren(root, false);
                    Collections.sort(children);
                    String myNodeName = pathName.substring(root.length() + 1);

                    if (myNodeName.equals(children.get(0))) {
                        becomeLeader();
                        return; // We are the leader, exit the check loop.
                    }

                    int myIndex = children.indexOf(myNodeName);
                    String nodeToWatch = children.get(myIndex - 1);
                    System.out.println("Watching " + root + "/" + nodeToWatch);
                    Stat s = zk.exists(root + "/" + nodeToWatch, true);

                    if (s != null) {
                        mutex.wait();
                    }
                }
            }
        }
        
        @Override
        synchronized public void process(WatchedEvent event) {
            synchronized (mutex) {
                if (event.getType() == Event.EventType.NodeDeleted) {
                    mutex.notifyAll();
                }
            }
        }
        
        private void becomeLeader() throws KeeperException, InterruptedException {
            System.out.println("Became a leader: " + id + "!");
            Stat s = zk.exists(leaderNodePath, false);
            if (s == null) {
                zk.create(leaderNodePath, id.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } else {
                zk.setData(leaderNodePath, id.getBytes(), -1);
            }
        }

        /**
         * Steps down from leadership, deleting the election node.
         */
        public void stepDown() throws KeeperException, InterruptedException {
            if (pathName != null) {
                zk.delete(pathName, -1);
            }
        }
    }


    /**
     * DataStore:
     * Store and retrieve byte data at specific ZooKeeper node addresses.
     */
    static public class DataStore extends SyncPrimitive {
        
        public DataStore(String address) {
            super(address);
        }

        /**
         * Store data at the given node path.
         * @param nodePath The ZooKeeper node path to store data at
         * @param data The data to store
         * @return true if successful
         */
        public boolean store(String nodePath, byte[] data) throws KeeperException, InterruptedException {
            try {
                Stat s = zk.exists(nodePath, false);
                if (s == null) {
                    // Create the node and any parent paths if they don't exist
                    ensurePathExists(nodePath.substring(0, nodePath.lastIndexOf('/')));
                    zk.create(nodePath, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } else {
                    zk.setData(nodePath, data, -1);
                }
                return true;
            } catch (KeeperException e) {
                System.out.println("Error storing data at " + nodePath + ": " + e.toString());
                return false;
            }
        }

        /**
         * Retrieve data from the given node path.
         * @param nodePath The ZooKeeper node path to retrieve data from
         * @return The data as byte array, or null if node doesn't exist
         */
        public byte[] retrieve(String nodePath) throws KeeperException, InterruptedException {
            try {
                return zk.getData(nodePath, false, null);
            } catch (KeeperException.NoNodeException e) {
                return null;
            }
        }
    }
    // --- Main methods for testing ---
    
    public static void leaderElectionTest(String args[]) {
        Random rand = new Random();
        int r = rand.nextInt(1000000);
        Leader leader = new Leader(args[0], "/election", "/leader", r);
        try {
            leader.elect();
            System.out.println("I am leader, I will do my work for 10 seconds.");
            Thread.sleep(10000);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                System.out.println("Stepping down...");
                leader.stepDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void queueTest(String args[]) {
        Queue q = new Queue(args[1], "/app3");
        int max = Integer.valueOf(args[2]);

        if (args[3].equals("p")) {
            System.out.println("Producer");
            for (int i = 0; i < max; i++) {
                try {
                    q.produce(10 + i);
                } catch (KeeperException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("Consumer");
            for (int i = 0; i < max; i++) {
                try {
                    int r = q.consume();
                    System.out.println("Item: " + r);
                } catch (KeeperException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void barrierTest(String args[]) {
        Barrier b = new Barrier(args[1], "/b1", Integer.valueOf(args[2]));
        try {
            boolean flag = b.enter();
            System.out.println("Entered barrier: " + args[2]);
            if (!flag) System.out.println("Error when entering the barrier");
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }

        Random rand = new Random();
        int r = rand.nextInt(100);
        System.out.println("Doing work for " + r + " iterations...");
        for (int i = 0; i < r; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        try {
            b.leave();
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Left barrier");
    }

    public static void lockTest(String args[]) {
        Lock lock = new Lock(args[1], "/lock");
        try {
            if (lock.lock()) {
                System.out.println("Lock acquired. Doing work for " + args[2] + " ms.");
                Thread.sleep(Long.valueOf(args[2]));
                lock.unlock();
                System.out.println("Lock released.");
            }
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void ensurePathExists(String path) throws KeeperException, InterruptedException {
        String[] parts = path.split("/");
        String current = "";
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current += "/" + part;
            Stat s = zk.exists(current, false);
            if (s == null) {
                zk.create(current, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }
    }

    public static void main(String args[]) {
        if (args[0].equals("qTest"))
            queueTest(args);
        else if (args[0].equals("barrier"))
            barrierTest(args);
        else if (args[0].equals("lock"))
            lockTest(args);
        else if (args[0].equals("leader"))
            leaderElectionTest(args);
        else
            System.err.println("Unknown option");
    }
}