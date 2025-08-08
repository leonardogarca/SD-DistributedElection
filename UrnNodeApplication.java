public class UrnNodeApplication {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: UrnNodeApplication <regionId> <urnId>");
            System.exit(1);
        }

        String regionId = args[0];
        String urnId = args[1];
        String zkAddress = System.getenv().getOrDefault("ZK_CONNECT_STRING", "localhost:2181");

        byte[] buDataBytes = BuReader.readBuDataAsBytes(urnId, regionId);
        SyncPrimitive.Queue queue = new SyncPrimitive.Queue(zkAddress, "/tasks/" + regionId);
        queue.produce(buDataBytes);

        System.out.println("Urn Node " + urnId + " submitted BU data to region " + regionId);
    }
}