package pt.uc.dei.student;

import pt.uc.dei.student.elections.Candidacy;
import pt.uc.dei.student.elections.Department;
import pt.uc.dei.student.elections.Election;
import pt.uc.dei.student.elections.Person;
import pt.uc.dei.student.others.Utilitary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.sql.SQLOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.SplittableRandom;

public class MulticastServer extends Thread {
    private final String MULTICAST_ADDRESS = "224.3.2.1";
    public final static int MULTICAST_PORT = 7002;
    MulticastSocket socket;
    InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);

    private final long SLEEP_TIME = 5000;
    private boolean isON = true;
    private final String OPTION_STRING = ">>> ";

    private int multicastId;
    private RMI rmiServer;
    private Department department;

    static MulticastServer multicastServer;

    private final NotifierCallBack NOTIFIER = new NotifierCallBack();

    private final HashMap<String, Boolean> availableTerminals = new HashMap<>();

    public MulticastServer(RMI rmiServer) throws IOException {
        this.rmiServer = rmiServer;
        this.socket = new MulticastSocket(MULTICAST_PORT);
    }

    public void menuPollingStation(int dep_id) throws IOException {
        int command = -1, command2 = -1, election = -1, n_dep = -1, campo_num = -1;
        String campo = "", campo_sql = "";
        Scanner input = new Scanner(System.in);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            ArrayList<Election> currentElections = this.rmiServer.getCurrentElections(dep_id);
            while (!rmiServer.hasElection(election, currentElections)) {
                System.out.println("Eleições a decorrer: ");
                Utilitary.listElections(currentElections);
                System.out.print(OPTION_STRING);
                election = input.nextInt();
            }
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }

        while (!(command >= 1 && command <= 6)) {
            System.out.println("Identificação de eleitor: ");
            System.out.println("Pesquisar por: ");
            System.out.println("\t(1)- Nome");
            System.out.println("\t(2)- Cargo");
            System.out.println("\t(3)- Departamento");
            System.out.println("\t(4)- Número de telemóvel");
            System.out.println("\t(5)- Morada");
            System.out.println("\t(6)- Número de cartão de cidadão");
            System.out.print(OPTION_STRING);
            command = input.nextInt();
        }

        if (command == 1) {
            campo_sql = "name";
            System.out.print("Introduza o seu nome: ");
            campo = reader.readLine();
        } else if (command == 2) {
            campo_sql = "job";
            int cargo = -1;
            while (!(cargo >= 1 && cargo <= 3)) {
                System.out.println("Introduza o cargo que ocupa: ");
                System.out.println("\t(1)- Estudante");
                System.out.println("\t(2)- Docente");
                System.out.println("\t(3)- Funcionário");
                cargo = input.nextInt();
                campo = Utilitary.decideCargo(cargo);
            }
        } else if (command == 3) {
            campo_sql = "department_id";
            try {
                while (!(campo_num >= 1 && campo_num <= 11)) {
                    System.out.println("Escolha o departamento que frequenta: ");
                    Utilitary.listDepart(this.rmiServer.getDepartments());
                    campo_num = input.nextInt();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (command == 4) {
            campo_sql = "phone";
            System.out.print("Introduza o seu número de telemóvel: ");
            campo_num = input.nextInt();
        } else if (command == 5) {
            campo_sql = "address";
            System.out.print("Introduza a sua morada: ");
            campo = reader.readLine();
        } else if (command == 6) {
            campo_sql = "cc_number";
            System.out.print("Introduza o seu número de cartão de cidadão: ");
            campo_num = input.nextInt();
        }

        try {
            ArrayList<Person> people = this.rmiServer.getRegisPeople(election, dep_id, campo, campo_sql, campo_num);
            while (!(command2 >= 1 && command2 <= people.size() + 1)) {
                System.out.println("Escolher Pessoa de acordo com o número de cartão de cidadão");
                Utilitary.listPerson(people);
                System.out.printf("\t(%s)- Nenhuma das anteriores\n", people.size() + 1);
                System.out.print(OPTION_STRING);
                command2 = input.nextInt();
            }
            if (command2 == people.size() + 1) {
                System.out.println("Não pode votar nesta eleição!");
            } else {
                //select voting terminal
                selectTerminal(people.get(command2 - 1).getCc_number(), election);

            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void selectTerminal(int cc_number, int election_id) {
        String id = null;
        // choose terminal
        while (id == null) {
            for (String key : availableTerminals.keySet()) {
                if (availableTerminals.get(key)) {
                    id = key;
                    availableTerminals.put(key, false);
                    break;
                }
            }
        }
        //send to voting terminal the cc
        String message = String.format("sender|multicast-%s-%s;destination|%s;message|identify;cc|%d;election_id|%s", this.getMulticastId(), this.department.getId(), id, cc_number, election_id);
        this.send(message);
        String[] getId = id.split("-");
        System.out.println("Desbloqueado terminal " + getId[1]);
    }

    private void connect() {
        String depName = this.department.getName();
        if (depName != null) {
            System.out.printf("======== Mesa de Voto #%s (%s) ========%n", this.getMulticastId(), depName);
            while (true) {
                try {
                    this.menuPollingStation(this.department.getId());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void run() {
        long counter = 0;
        try {
            //O servidor não recebe mensagens dos clientes (sem o Port)
            socket.joinGroup(group); // Para o servidor receber mensagens dar join ao grupo
            while (isON) {
                /*
                RECEBER E PARSE DO PACOTE
                 */
                byte[] bufferReceive = new byte[256];
                DatagramPacket packet = new DatagramPacket(bufferReceive, bufferReceive.length);
                socket.receive(packet);
                HashMap<String, String> msgHash = Utilitary.parseMessage(new String(packet.getData(), 0, packet.getLength()));
                /*
                USAR A INFORMACOES DO PACOTE
                 */
                this.doThings(msgHash);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void send(String message) {
        byte[] buffer = message.getBytes();
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, this.group, MULTICAST_PORT);
        try {
            this.socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doThings(HashMap<String, String> msgHash) {
        //nao ler as suas proprias mensagens
        if (!msgHash.get("sender").startsWith("multicast")) {
            switch (msgHash.get("message")) {
                case "occupied":
                case "available":
                    registerTerminal(msgHash.get("sender"), msgHash.get("message"));
                    break;
                case "login":
                    this.verifyLogin(msgHash.get("username"), msgHash.get("password"));
                    break;
                case "logged in":
                    this.sendVotingForm();
                    break;
            }
        }
    }

    private void registerTerminal(String id, String status) {
        String message = String.format("sender|multicast-%s-%s;destination|%s;message|true", this.getMulticastId(), this.department.getId(), id);
        if (status.equals("available")) {
            availableTerminals.put(id, true);
        } else {
            availableTerminals.put(id, false);
        }
        this.send(message);
    }

    private void verifyLogin(String username, String password) {
        String message;
        try {
            if (this.getRmiServer().getPerson(username, password) != null) {
                //TODO id tem que ser específico de um terminal de voto, ver exemplo na função registerTerminal
                message = String.format("sender|multicast-%s-%s;destination|%s;message|logged in", this.getMulticastId(), this.department.getId(), "voteterm");
            } else {
                //TODO same here
                message = String.format("sender|multicast-%s-%s;destination|%s;message|wrong password", this.getMulticastId(), this.department.getId(), "voteterm");
            }
        } catch (RemoteException | InterruptedException e) {
            //TODO aaaaaaand here
            message = String.format("sender|multicast-%s-%s;destination|%s;message|wrong password", this.getMulticastId(), this.department.getId(), "voteterm");
        }
        this.send(message);
    }

    private void sendVotingForm(int election_id){
        String votingForm = "Candidatos:\n";
        try {
            for (Candidacy c : this.getRmiServer().getCandidacies(election_id)) {
                votingForm += String.format("\t(%s)- %s\n", c.getId(), c.getName());
            }
        }catch(RemoteException | InterruptedException e){
            e.printStackTrace();
        }
        votingForm += "(-1)- Voto Branco\n";
        votingForm += "(-2)- Voto Nulo\n";
        String message = String.format("sender|multicast-%s-%s;destination|%s;message|%s", this.getMulticastId(), this.department.getId(), "voteterm",votingForm);
        this.send(message);
        //TODO
    }


    void listElections(ArrayList<Election> elections) {
        try {
            if (this.rmiServer.numElections() > 0) {
                for (Election e : elections) {
                    System.out.printf("\t(%s)- %s\n", elections.indexOf(e) + 1, e.getTitle());
                }
            } else {
                System.out.println("Não existem eleições\n");
            }
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void reconnectToRMI() {
        while (true) {
            try {
                RMI rmiServer = (RMI) LocateRegistry.getRegistry(RMIServer.RMI_PORT).lookup("clientMulticast");
                multicastServer = new MulticastServer(rmiServer);
                break;
            } catch (NotBoundException | IOException remoteException) {
                remoteException.printStackTrace();
            }
        }
    }

    public RMI getRmiServer() {
        return this.rmiServer;
    }
    public int getMulticastId() {
        return this.multicastId;
    }

    public void setMulticastId(int multicastId) {
        this.multicastId = multicastId;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public static void main(String[] args) {
        try {
            int dep = -1;
            Scanner input = new Scanner(System.in);
            RMI rmiServer = (RMI) LocateRegistry.getRegistry(RMIServer.RMI_PORT).lookup("clientMulticast");
            multicastServer = new MulticastServer(rmiServer);
            /*
            SETUP
             */
            ArrayList<Department> departments = multicastServer.rmiServer.getDepartments();
            while (!(dep >= 1 && dep <= 11)) {
                System.out.println("Departamento onde se localiza: ");
                Utilitary.listDepart(departments);
                System.out.print(">>> ");
                dep = input.nextInt();
            }
            if (rmiServer.initializeMulticast(dep, multicastServer.NOTIFIER) != null) {
                multicastServer.setMulticastId(dep);
                multicastServer.setDepartment(departments.get(dep - 1));
            /*
            LIGAR
             */
                multicastServer.start();
                multicastServer.connect();
            } else
                System.exit(0);
        } catch (Exception e) {
            while (true) {
                try {
                    RMI rmiServer = (RMI) LocateRegistry.getRegistry(RMIServer.RMI_PORT).lookup("clientMulticast");
                    multicastServer = new MulticastServer(rmiServer);
                    break;
                } catch (NotBoundException | IOException remoteException) {
                    remoteException.printStackTrace();
                }
            }
        }
    }
}
