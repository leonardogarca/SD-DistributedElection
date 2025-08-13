To run `UrnNodeApplication`, follow these steps:

---

### **1. Compile the Code**

Make sure you have all dependencies in lib (Gson, ZooKeeper, SLF4J, etc.).

#### **On Linux/macOS:**
```sh
javac -cp "lib/*" UrnNodeApplication.java common/*.java
```

#### **On Windows CMD:**
```bat
javac -cp "lib/*" UrnNodeApplication.java common\*.java
```

---

### **2. Run the Application**

Choose a config file (e.g., urn1.config.json or urn2.config.json):

#### **On Linux/macOS:**
```sh
java -cp ".:lib/*" UrnNodeApplication urns/urn1.config.json
```

#### **On Windows CMD:**
```bat
java -cp ".;lib/*" UrnNodeApplication urns\urn1.config.json
```

You can run multiple instances (in separate terminals) with different config files to simulate multiple urns.

---

### **3. Make Sure ZooKeeper is Running**

Start ZooKeeper before running your application:

#### **On Linux/macOS:**
```sh
zkServer.sh start
```

#### **On Windows CMD:**
```bat
zkServer.cmd
```
