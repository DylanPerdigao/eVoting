package pt.uc.dei.student;

import pt.uc.dei.student.elections.*;

import java.io.IOException;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Thread.sleep;

public class RMIServer extends UnicastRemoteObject implements RMI {
    private final int NUM_MULTICAST_SERVERS = 11;


    private final String SERVER_ADDRESS = "127.0.0.1";
    public final static int SERVER_PORT = 7001;
    public final static int RMI_PORT = 7000;
    static ConcurrentHashMap<Integer, Notifier> notifiersMulticast;
    static ArrayList<Notifier> notifiersAdmin;
    public StatusChecker statcheck;

    private Boolean terminalsUpdated;

    public RMIServer() throws RemoteException {
        super();
        notifiersMulticast = new ConcurrentHashMap<>();
        notifiersAdmin = new ArrayList<>();
        this.terminalsUpdated=false;
    }


    /**
     * Função de inserção de uma determinada pessoa na base de dados
     *
     * @param cargo       Cargo (Estudante, Docente ou Funcionário)
     * @param pass        código de acesso para aceder a uma determinada eleição
     * @param dep         Departamento a que a pessoa pertence
     * @param num_phone   Número de telemóvel
     * @param address     Morada
     * @param num_cc      Número de cartão de cidadão
     * @param cc_validity Formato da data (ano, mes, dia)
     * @return true ou false caso tenha sido inserido com sucesso ou não na base de dados
     */
    public boolean insertPerson(String name, String cargo, String pass, int dep, int num_phone, String address, int num_cc, String cc_validity) {
        String sql = String.format("INSERT INTO person(name,job,password,department_id,phone,address,cc_number,cc_validity) VALUES('%s','%s','%s',%s,%s,'%s',%s,'%s')", name, cargo, pass, dep, num_phone, address, num_cc, cc_validity);
        if (sql == null) return false;
        return updateOnDB(sql);
    }

    /**
     * @param titulo    Título da eleição
     * @param descricao Breve descrição da eleição
     * @return true ou false caso tenha sido inserido com sucesso ou não na base de dados
     */
    public int insertElection(String begin_data, String end_data, String titulo, String descricao, String type_ele) {
        String dataIni = String.format("%s:00", begin_data), dataFim = String.format("%s:00", end_data);
        String sql = String.format("INSERT INTO election(title,type,description,begin_date,end_date) VALUES('%s','%s','%s','%s','%s')", titulo, type_ele, descricao, dataIni, dataFim);
        if (sql == null) return -1;
        Connection conn = connectDB();
        try {
            conn.setAutoCommit(false);
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(sql);
            Statement stmt2 = conn.createStatement();
            ResultSet generate_keys = stmt2.executeQuery("SELECT last_insert_rowid()");
            generate_keys.next();
            int auto_id = generate_keys.getInt(1);
            conn.commit();
            stmt.close();
            conn.close();
            return auto_id;
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return -1;
        }
    }

    public boolean insertElectionDepartment(int id_election, int id_dep) {
        String sql = String.format("INSERT INTO election_department(election_id,department_id) VALUES(%s,%s)", id_election, id_dep);
        return updateOnDB(sql);
    }

    public boolean insertCandidacyIntoElection(String name, String type, int election_id) {
        return this.updateOnDB(String.format("INSERT INTO candidacy(id,name,type,election_id) VALUES (NULL,'%s','%s',%s);", name, type, election_id));
    }

    public boolean insertPersonIntoCandidacy(int candidacy_id, int cc_number) {
        return this.updateOnDB(String.format("INSERT INTO candidacy_person(candidacy_id,person_cc_number) VALUES (%s,%s);", candidacy_id, cc_number));
    }

    public ArrayList<Election> getElections() {
        return this.selectElections("SELECT * FROM election");
    }

    public ArrayList<Election> getElectionsNotStarted() {
        return this.selectElections("SELECT * FROM election where begin_date > date('now') AND end_date > date('now')");
    }

    public ArrayList<Candidacy> getCandidacies(int election_id) {
        return this.selectCandidacies("SELECT * FROM candidacy WHERE election_id = " + election_id);
    }

    public Person getPerson(String username, String password) {
        try {
            ArrayList<Person> people = this.selectPeople("SELECT * FROM person WHERE cc_number='" + username + "' AND password='" + password + "';");
            return people.get(0);
        } catch (NullPointerException e) {
            return null;
        }
    }

    public ArrayList<Person> getPeople(int candidacy_id) {
        return this.selectPeople(
                "SELECT *\n" +
                        "FROM person\n" +
                        "WHERE cc_number IN (\n" +
                        "    SELECT person_cc_number\n" +
                        "    FROM candidacy_person\n" +
                        "    WHERE candidacy_id = " + candidacy_id + "\n" +
                        "); "
        );
    }

    public void updateElections(Election e) {
        if (this.updateOnDB(String.format("UPDATE election SET title='%s',type='%s',description='%s',begin_date='%s',end_date='%s' WHERE id=%s", e.getTitle(), e.getType(), e.getDescription(), e.getBegin().toString(), e.getEnd().toString(), e.getId()))) {
            System.out.println("Successfully updated election");
        } else {
            System.out.println("Problem updating election");
        }
    }
    public void updateDepartmentMulticast(int id) {
        if (this.updateOnDB(String.format("UPDATE department SET hasmulticastserver=null WHERE id=%s", id))) {
            System.out.println("Successfully updated department");
        } else {
            System.out.println("Problem updating department");
        }
    }

    synchronized public void updateTerminals(int department_id, ConcurrentHashMap<String, Boolean> availableTerminals) {
        while(this.terminalsUpdated){
            try{
                wait();
            }catch(InterruptedException ignore){}
        }
        this.updateOnDB("DELETE FROM voting_terminal WHERE department_id="+department_id);
        for (String t : availableTerminals.keySet()) {
            if (availableTerminals.get(t)) {
                this.updateOnDB(String.format("INSERT INTO voting_terminal(id,department_id) VALUES (%s,%s)", t.split("-")[1], department_id));
            }
        }
        this.terminalsUpdated=true;
        notify();

    }

    public void removeOnDB(String table, String idName, int id) {
        if (this.updateOnDB("DELETE FROM " + table + " WHERE " + idName + " = " + id)) {
            System.out.println("Removed from" + table + " id #" + id);
        } else {
            System.out.println("Problem removing id #" + id + " from database");
        }
    }

    public ArrayList<Department> getDepartments() {
        return this.selectDepartments("SELECT * FROM department");
    }

    public ArrayList<Department> getActiveMulticasts() {
        return this.selectDepartments("SELECT * FROM department WHERE hasmulticastserver=1");
    }

    synchronized public ConcurrentHashMap<Integer, ArrayList<Integer>> getActiveTerminals() {
        while(!this.terminalsUpdated){
            try{
                wait();
            }catch(InterruptedException ignore){}
        }
        ConcurrentHashMap<Integer, ArrayList<Integer>> hashMap = this.selectActiveTerminals("SELECT * FROM voting_terminal");
        this.terminalsUpdated=false;
        notify();
        return hashMap;

    }

    /**
     * Conexão à base de dados
     *
     * @return conn
     */
    public Connection connectDB() {
        String url = "jdbc:sqlite:Election.db";
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return conn;
    }

    /**
     * Insere/Remove na base de dados na respetivas tabela
     *
     * @param sql commando sql
     * @return true ou false dependendo se a inserção teve ou não sucesso
     */
    public boolean updateOnDB(String sql) {
        Connection conn = connectDB();
        try {
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(sql);
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Seleciona eleições na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ArrayList<Election> selectElections(String sql) {
        Connection conn = connectDB();
        ArrayList<Election> elections = new ArrayList();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                elections.add(new Election(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("type"),
                        rs.getString("description"),
                        rs.getString("begin_date"),
                        rs.getString("end_date")
                ));
            }
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return elections;
    }

    /**
     * Seleciona listas(candidaturas) na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ArrayList<Candidacy> selectCandidacies(String sql) {
        Connection conn = connectDB();
        ArrayList<Candidacy> candidacies = new ArrayList();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                candidacies.add(new Candidacy(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("type")
                ));
            }
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return candidacies;
    }

    /**
     * Seleciona listas(candidaturas) na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ArrayList<Person> selectPeople(String sql) {
        Connection conn = connectDB();
        ArrayList<Person> people = new ArrayList();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                people.add(new Person(
                        rs.getString("name"),
                        rs.getInt("cc_number"),
                        rs.getString("cc_validity"),
                        rs.getString("password"),
                        rs.getString("address"),
                        rs.getInt("phone"),
                        rs.getString("job"),
                        rs.getInt("department_id")
                ));
            }
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return people;
    }

    /**
     * Seleciona departamentos na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ArrayList<Department> selectDepartments(String sql) {
        Connection conn = connectDB();
        ArrayList<Department> departments = new ArrayList();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                departments.add(new Department(
                        rs.getInt("id"),
                        rs.getString("name")
                ));
            }
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return departments;
    }

    /**
     * Seleciona departamentos na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ArrayList<VotingRecord> selectVotingRecords(String sql) {
        Connection conn = connectDB();
        ArrayList<VotingRecord> votingRecords = new ArrayList();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                votingRecords.add(new VotingRecord(
                        formatter.parse(rs.getString("date")),
                        rs.getString("d_name"),
                        rs.getString("p_name"),
                        rs.getString("title")
                ));
            }
            stmt.close();
            conn.close();
        } catch (SQLException | ParseException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return votingRecords;
    }

    /**
     * Seleciona os terminais de voto na base de dados
     *
     * @param sql commando sql
     * @return devolve o resultado da query ou null
     */
    public ConcurrentHashMap<Integer, ArrayList<Integer>> selectActiveTerminals(String sql) {
        Connection conn = connectDB();
        ConcurrentHashMap<Integer, ArrayList<Integer>> depToTerm = new ConcurrentHashMap<>();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            ArrayList<Integer> terminals;
            while (rs.next()) {
                int key = rs.getInt("department_id");
                int value = rs.getInt("id");
                if (depToTerm.containsKey(key)) {
                    terminals = depToTerm.get(key);
                    terminals.add(value);
                } else {
                    terminals = new ArrayList<Integer>();
                    terminals.add(value);
                    depToTerm.put(key, terminals);
                }
            }
            stmt.close();
            conn.close();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
        return depToTerm;
    }

    public ArrayList<Department> selectPollingStation(int election_id) {
        if (election_id == -1) {
            return selectDepartments("SELECT id, name FROM department WHERE hasmulticastserver = 1");
        } else {
            return selectDepartments("SELECT id, name FROM department, election_department " +
                    "WHERE department.id = election_department.department_id AND election_department.election_id = " + election_id);
        }
    }

    public ArrayList<Department> selectNoAssociatedPollingStation(int election_id) {
        return selectDepartments("SELECT id, name FROM department WHERE department.hasmulticastserver = 1 " +
                "EXCEPT " +
                "SELECT id, name FROM department, election_department " +
                "WHERE department.id = election_department.department_id AND election_department.election_id = " + election_id);
    }

    public int countRowsBD(String sql, String returnCount) {
        Connection conn = connectDB();
        try {
            Statement stmt = conn.createStatement();
            ResultSet res;
            if (returnCount == null)
                res = stmt.executeQuery("SELECT COUNT(*) FROM " + sql);
            else res = stmt.executeQuery("SELECT " + returnCount + " FROM " + sql);
            int count = res.getInt(1);
            stmt.close();
            conn.close();
            return count;
        } catch (Exception e) {
            System.out.println("Erro a contar o número de linhas da tabela");
            e.printStackTrace();
        }
        return 0;
    }

    public int numElections() {
        return countRowsBD("election", null);
    }

    public void removePollingStation(int department_id) {
        removeOnDB("election_department", "department_id", department_id);
    }

    public void insertPollingStation(int election_id, int department_id) {
        insertElectionDepartment(election_id, department_id);
    }

    public boolean turnOffPollingStation(int department_id) {
        return updateOnDB("UPDATE department SET hasMulticastServer = null WHERE id = " + department_id);
    }

    public ArrayList<Election> getEndedElections() {
        return selectElections("SELECT * FROM election WHERE end_date < date('now')");
    }

    public ArrayList<Election> getCurrentElections(int department_id) {
        return selectElections("SELECT * FROM election, election_department WHERE begin_date <= date('now') AND end_date >= date('now') AND election.id = election_department.election_id AND (department_id = " + department_id + " OR department_id = -1)");
    }

    public int getBlackVotes(int id_election) {
        return countRowsBD("election WHERE id = " + id_election, "blank_votes");
    }

    public int getNullVotes(int id_election) {
        return countRowsBD("election WHERE id = " + id_election, "null_votes");
    }

    public int getVotesCandidacy(int id_election, int id_candidacy) {
        return countRowsBD("candidacy WHERE id = " + id_candidacy + " and election_id = " + id_election, "votes");
    }

    public float getPercentVotesCandidacy(int id_election, int id_candidacy) {
        int totalVotes = countRowsBD("candidacy", "SUM(votes)") + getBlackVotes(id_election) + getNullVotes(id_election);
        if (id_candidacy == -1) return ((float) getBlackVotes(id_election) / (float) totalVotes) * 100;
        else return ((float) getVotesCandidacy(id_election, id_candidacy) / (float) totalVotes) * 100;
    }

    public ArrayList<Person> getRegisPeople(int election_id, int department_id, String campo, String campo_sql, int campo_num) {
        // Verificar se a eleição está restringida a um departamento
        int ndep = countRowsBD("election_department WHERE election_id = " + election_id, "department_id");
        if (ndep == -1) {
            if (campo_num == -1) {
                if (campo_sql.equals("name") || campo_sql.equals("address")) {
                    return selectPeople("SELECT DISTINCT * FROM person " +
                            "JOIN election_department ed " +
                            "JOIN election e on ed.election_id = e.id AND e.type = person.job AND ed.election_id = " + election_id +
                            " AND person." + campo_sql + " like '%" + campo + "%' AND ed.department_id = -1");

                }
                return selectPeople("SELECT DISTINCT * " +
                        "FROM person JOIN election_department ed JOIN election e on ed.election_id = e.id AND e.type = person.job" +
                        " AND ed.election_id = " + election_id + " AND person." + campo_sql + " = '" + campo + "' AND ed.department_id = -1");
            } else {
                return selectPeople("SELECT DISTINCT * " +
                        "FROM person JOIN election_department ed JOIN election e on ed.election_id = e.id AND e.type = person.job" +
                        " AND ed.election_id = " + election_id + " AND person." + campo_sql + " = " + campo_num + " AND ed.department_id = -1");
            }
        } else { // Caso esteja restringido a um determinado departamento
            if (campo_num == -1) {
                if (campo_sql.equals("name") || campo_sql.equals("address")) {
                    return selectPeople("SELECT DISTINCT * " +
                            "FROM person JOIN election_department ed on person.department_id = ed.department_id JOIN election e on ed.election_id = e.id AND e.type = person.job" +
                            " AND ed.department_id = " + department_id + " AND ed.election_id = " + election_id + " AND person." + campo_sql + " like '%" + campo + "%'");
                }
                return selectPeople("SELECT DISTINCT * " +
                        "FROM person JOIN election_department ed on person.department_id = ed.department_id JOIN election e on ed.election_id = e.id AND e.type = person.job" +
                        " AND ed.department_id = " + department_id + " AND ed.election_id = " + election_id + " AND person." + campo_sql + " = '" + campo + "'");
            } else {
                return selectPeople("SELECT DISTINCT * " +
                        "FROM person JOIN election_department ed on person.department_id = ed.department_id JOIN election e on ed.election_id = e.id AND e.type = person.job" +
                        " AND ed.department_id = " + department_id + " AND ed.election_id = " + election_id + " AND person." + campo_sql + " = " + campo_num + "");
            }
        }
    }

    public void insertVotingRecord(String id_election, String cc, String ndep) {
        updateOnDB(String.format("INSERT INTO voting_record(vote_date,department,person_cc_number,election_id) VALUES(datetime('now'),'%s','%s','%s')", ndep, cc, id_election));
    }

    public void updateCandidacyVotes(String id_election, String candidacyOption, String cc, String ndep) {
        insertVotingRecord(id_election, cc, ndep);
        updateOnDB("UPDATE candidacy SET votes = votes + 1 WHERE election_id = " + id_election + " AND id = " + candidacyOption);
        this.sendRealTimeInfo();
    }

    public void updateBlankVotes(String id_election, String cc, String ndep) {
        insertVotingRecord(id_election, cc, ndep);
        updateOnDB("UPDATE election SET blank_votes = blank_votes + 1 WHERE id = " + id_election);
        this.sendRealTimeInfo();
    }

    public void updateNullVotes(String id_election, String cc, String ndep) {
        insertVotingRecord(id_election, cc, ndep);
        updateOnDB("UPDATE electio SET null_votes = null_votes + 1 WHERE id = " + id_election);
        this.sendRealTimeInfo();
    }

    public void sendRealTimeInfo() {
        String sql = "SELECT count(*), d.name as Name, e.title as Title" +
                " FROM voting_record" +
                " JOIN department d on voting_record.department = d.id" +
                " JOIN election e on e.id = voting_record.election_id" +
                " WHERE e.begin_date < date('now') AND e.end_date > date('now') GROUP BY voting_record.department, voting_record.election_id";
        ArrayList<InfoElectors> info = new ArrayList<>();
        Connection conn = connectDB();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                info.add(new InfoElectors(
                        rs.getInt("count(*)"),
                        rs.getString("Name"),
                        rs.getString("Title")
                ));
            }
            stmt.close();
            conn.close();
            for (Notifier notifier : notifiersAdmin) {
                try {
                    notifier.updateAdmin(info);
                } catch (RemoteException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    public void sendRealTimeInfo(Notifier NOTIFIER) {
        String sql = "SELECT count(*), d.name as Name, e.title as Title" +
                " FROM voting_record" +
                " JOIN department d on voting_record.department = d.id" +
                " JOIN election e on e.id = voting_record.election_id" +
                " WHERE e.begin_date < date('now') AND e.end_date > date('now') group by voting_record.department, voting_record.election_id";
        ArrayList<InfoElectors> info = new ArrayList<>();
        Connection conn = connectDB();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                info.add(new InfoElectors(
                        rs.getInt("count(*)"),
                        rs.getString("Name"),
                        rs.getString("Title")
                ));
            }
            stmt.close();
            conn.close();
            try {
                NOTIFIER.updateAdmin(info);
            } catch (RemoteException | InterruptedException e) {
                e.printStackTrace();
            }

        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }


    public boolean checkIfAlreadyVote(int cc, int election) {
        return countRowsBD("voting_record WHERE person_cc_number = " + cc + " AND election_id = " + election, null) > 0;
    }

    public ArrayList<VotingRecord> getVotingRecords() {
        return selectVotingRecords("select vote_date as date, d.name as d_name, p.name as p_name, title FROM voting_record " +
                "JOIN person p on voting_record.person_cc_number = p.cc_number " +
                "JOIN election e on voting_record.election_id = e.id " +
                "JOIN department d on d.id = voting_record.department " +
                "ORDER BY voting_record.election_id, p.name");
    }

    public String saySomething() throws RemoteException {
        return "I'm alive!";
    }

    /**
     * @return true ou false caso o servidor secundário consiga enviar com sucesso ou não pings ao servidor primário
     */
    public boolean isPrimaryFunctional() {
        DatagramSocket aSocket = null;
        boolean ok = true;
        try {
            aSocket = new DatagramSocket();
            System.out.print("Mensagem a enviar = ");

            InetAddress aHost = InetAddress.getByName(this.SERVER_ADDRESS);
            byte[] m = "Pinging".getBytes();
            DatagramPacket request = new DatagramPacket(m, m.length, aHost, this.SERVER_PORT);
            aSocket.send(request);
            aSocket.setSoTimeout(200);
            byte[] buffer = new byte[1000];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            aSocket.receive(reply);
            System.out.println("Recebeu: " + new String(reply.getData(), 0, reply.getLength()));
        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
            ok = false;
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            ok = false;
        } finally {
            if (aSocket != null) aSocket.close();
        }
        return ok;
    }

    public String initializeMulticast(int dep_id, Notifier NOTIFIER) {
        int numMulticast = countRowsBD("department WHERE hasMulticastServer = 1", null);
        if (numMulticast < NUM_MULTICAST_SERVERS && countRowsBD("department WHERE hasMulticastServer IS NULL AND id = " + dep_id, null) != 0) {
            if (!updateOnDB("UPDATE department SET hasMulticastServer = 1 WHERE id = " + dep_id)) {
                System.out.println("Impossível adicionar mesa de voto! :(");
                return null;
            } else {
                System.out.println("Mesa de voto criada com sucesso! :)");
                synchronized (notifiersMulticast) {
                    notifiersMulticast.put(dep_id, NOTIFIER);
                }
                try {
                    notifiersMulticast.get(dep_id).ping();
                } catch (RemoteException | InterruptedException e) {
                    e.printStackTrace();
                }
                return selectDepartments("SELECT * FROM department WHERE id = " + dep_id).get(0).getName();
            }
        }
        return null;
    }

    public void initializeRealTimeInfo(Notifier NOTIFIER) {
        notifiersAdmin.add(NOTIFIER);
        sendRealTimeInfo(NOTIFIER);
    }

    public void endRealTimeInfo(Notifier NOTIFIER) {
        notifiersAdmin.remove(NOTIFIER);
    }

    /**
     * Inicializa a ligação do Servidor RMI com o Servidor Multicast
     */
    public void initializeRMI() {
        System.out.println("Becoming Primary Server!");
        try {
            RMIServer obj = new RMIServer();
            Registry r = LocateRegistry.createRegistry(this.RMI_PORT);
            r.rebind("clientMulticast", obj);
            r.rebind("admin", obj);
            System.out.println("RMI Server ready!");
        } catch (RemoteException re) {
            System.out.println("Exception in Server.main: " + re);
        }
    }

    /**
     * Inicializa a ligação do servidor primário com o servidor secundário via UDP
     * Necessário para verificar se o servidor primária se mantém ligado, caso contrário o servidor secundário passa a ser primário
     * Quando o servidor primário que deixou de estar funcional se reconectar irá assumir o papel de servidor secundário
     */
    public void initializeUDP() {
        String s;
        try (DatagramSocket aSocket = new DatagramSocket(SERVER_PORT)) {
            System.out.println("Socket Datagram à escuta no porto 7001");
            while (true) {
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);
                s = new String(request.getData(), 0, request.getLength());
                System.out.println("Recebeu um ping: " + s);
                buffer = "I'm Alive".getBytes();
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length, request.getAddress(), request.getPort());
                aSocket.send(reply);
            }
        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    public ConcurrentHashMap<Integer, Notifier> getNotifiersMulticast(){
        return notifiersMulticast;
    }

    public static void main(String[] args) throws RemoteException {
        RMIServer rmiServer = new RMIServer();
        int numPingsFailed = 0;
        while (numPingsFailed < 5) {
            try {
                sleep(200);
                if (!rmiServer.isPrimaryFunctional())
                    numPingsFailed++;
                else numPingsFailed = 0;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        rmiServer.initializeRMI();
        rmiServer.initializeStatusChecker();
        rmiServer.initializeUDP();
    }

    private void initializeStatusChecker() {
        statcheck = new StatusChecker(notifiersMulticast, this);
    }
}
