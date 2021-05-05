package webServer.model;

import pt.uc.dei.student.elections.Candidacy;
import pt.uc.dei.student.elections.Department;
import pt.uc.dei.student.elections.Election;
import pt.uc.dei.student.elections.Person;
import pt.uc.dei.student.others.RMI;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Date;

public class HeyBean {
    private RMI server;
    private int ccnumber; // username and password supplied by the user
    private String password;
    private int election_id;
    private int candidacy_id;
    // Fields for register
    private String name, cargo, address, ccDate;
    private int phone, dep;


    public HeyBean() {
        // Connect to RMI Server
        try {
            server = (RMI) LocateRegistry.getRegistry("127.0.0.1", 7000).lookup("server");
//            server = (RMI) Naming.lookup("server");
        } catch (NotBoundException | RemoteException e) {
            e.printStackTrace();
        }
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCargo(String cargo) {
        this.cargo = cargo;
    }

    public void setDep(int dep) {
        this.dep = dep;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setPhone(int phone) {
        this.phone = phone;
    }

    public void setCcDate(String ccDate) {
        this.ccDate = ccDate;
    }

    public void setCcnumber(int ccnumber) {
        this.ccnumber = ccnumber;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setElection_id(int election_id) {
        this.election_id = election_id;
    }

    public void setCandidacy_id(int candidacy_id) {
        this.candidacy_id = candidacy_id;
    }

    public Person getUser() throws RemoteException {
        Person p = null;
        try {
            p = server.getPerson(String.valueOf(this.ccnumber), password);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return p;
    }

    public ArrayList<Election> getElections() {
        ArrayList<Election> elections = null;

        try {
            elections = server.getCurrentElectionsPerson(String.valueOf(ccnumber), String.valueOf(password));
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return elections;
    }

    public ArrayList<Candidacy> getCandidacies() {
        ArrayList<Candidacy> candidacies = null;
        try {
            candidacies = server.getCandidacies(this.election_id);
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return candidacies;
    }

    public ArrayList<Department> getDepartments() {
        ArrayList<Department> departments = null;
        try {
            departments = server.getDepartments();
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return departments;
    }

    public void updateVotes() {
        try {
            server.updateCandidacyVotes(String.valueOf(this.election_id), String.valueOf(this.candidacy_id), String.valueOf(this.ccnumber), String.valueOf(0));
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public boolean checkIfAlreadyVotes() {
        try {
            return server.checkIfAlreadyVote(this.ccnumber, this.election_id);
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean insertRegister() {
        try {
            return server.insertPerson(this.name, this.cargo, this.password, this.dep, this.phone, this.address, this.ccnumber, this.ccDate);
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }
}