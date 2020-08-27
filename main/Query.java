package flightapp;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
    // DB Connection
    private Connection conn;
    private static boolean loginStatus;
    private static HashMap<String, HashMap<Integer, Itinerary>> userIti = new HashMap<>();
    private String logginUser = "";

    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;

    // Canned queries
    private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
    private PreparedStatement checkFlightCapacityStatement;

    // For check dangling
    private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
    private PreparedStatement tranCountStatement;

    // Clear Tables
    private static final String CLEAR = "DELETE FROM Remainseat; DELETE FROM Reservations; DELETE FROM Users;";
    private PreparedStatement clearTables;

    // Create users
    private static final String CREATE_USER = "INSERT INTO Users VALUES(?, ?, ?, ?)";
    private PreparedStatement create_user;

    // Find users
    private static final String FIND_USER = "SELECT * FROM Users WHERE username = ?";
    private PreparedStatement find_user;

    // Search direct flights
    private static final String SEARCH_N_DIRECT =
            "SELECT TOP(?) fid, day_of_month, carrier_id, flight_num, " +
                    "origin_city, dest_city, actual_time, " +
                    "capacity, price "
                    + "FROM Flights" + " WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? " +
                    " AND canceled = 0 "  + " ORDER BY actual_time ASC, fid ASC;";
    private PreparedStatement search_n_direct;

    // Search one-hop flight
    private static final String SEARCH_INDIRECT =
            "SELECT TOP (?) F1.fid AS fid1 , F1.day_of_month AS day1, F1.carrier_id AS carrierid1, " +
                    "F1.flight_num AS flightnum1, F1.origin_city AS origincity1, F1.dest_city AS destcity1, " +
                    "F1.actual_time AS time1, F1.capacity AS capacity1, F1.price AS price1, "
                    + "F2.fid AS fid2, F2.day_of_month AS day2, F2.carrier_id as carrierid2, " +
                    "F2.flight_num AS flightnum2, F2.origin_city AS origincity2, F2.dest_city AS destcity2, " +
                    "F2.actual_time AS time2, F2.capacity AS capacity2, F2.price AS price2 "
                    + "FROM Flights F1, Flights F2 "
                    + "WHERE F1.canceled <> 1 AND F2.canceled <> 1 AND F1.origin_city = ? AND F1.dest_city = F2.origin_city "
                    + "AND F2.dest_city = ? AND F1.day_of_month = ? AND F1.day_of_month = F2.day_of_month "
                    + "ORDER BY (F1.actual_time + F2.actual_time) ASC, fid1 ASC, fid2 ASC";
    private PreparedStatement search_indirect;

    private static final String FIND_VALIDRESERVE = "SELECT * FROM Reservations WHERE ifcancelled = 0 AND ifpaid = 0 AND username = ? AND reserveID = ?";
    private PreparedStatement find_validreserve;

    private static final String FIND_CANCEL = "SELECT * FROM Reservations WHERE ifcancelled = 0 AND username = ? AND reserveID = ?";
    private PreparedStatement find_cancel;

    private static final String FIND_RESERVEID = "SELECT count(*) AS 'count' FROM Reservations";
    private PreparedStatement find_reseveid;

    private static final String ADD_RESERVE = "INSERT INTO Reservations VALUES(?,?,?,?,?,?,?,?,?,?)";
    private PreparedStatement add_reserve;

    private static final String CHECK_DATE = "SELECT dayOfMonth FROM Reservations WHERE dayOfMonth = ? AND username = ?";
    private PreparedStatement check_date;

    private static final String All_RESERVATION = "SELECT * FROM Reservations WHERE username = ? AND ifcancelled = 0 ORDER BY reserveID";
    private PreparedStatement all_reservation;

    private static final String CANCEL_RESERVATION = "UPDATE Reservations SET ifcancelled = 1, ifpaid = 0 WHERE reserveID = ? AND ifcancelled = 0";
    private PreparedStatement cancel_reservation;

    private static final String CHECK_BALANCE = "SELECT balance FROM Users WHERE username = ?";
    private PreparedStatement check_balance;

    private static final String UPDATE_BALANCE = "UPDATE Users SET balance = ? WHERE username = ?";
    private PreparedStatement update_balance;

    private static final String UPDATE_PAID = "UPDATE Reservations SET ifpaid = 1 WHERE reserveID = ?";
    private PreparedStatement update_paid;

    private static final String INSERT_SEATS = "INSERT INTO Remainseat VALUES(?,?)";
    private PreparedStatement insert_seats;

    private static final String CHECK_SEATS = "SELECT seats FROM Remainseat WHERE fid = ?";
    private PreparedStatement check_seats;

    private static final String UPDATE_SEATS = "UPDATE Remainseat SET seats = ? WHERE fid = ?";
    private PreparedStatement update_seats;

    private static final String FIND_FLIGHTS = "SELECT * from Flights WHERE fid = ?";
    private PreparedStatement find_flights;


    public Query() throws SQLException, IOException {
        this(null, null, null, null);
    }

    protected Query(String serverURL, String dbName, String adminName, String password)
            throws SQLException, IOException {
        conn = serverURL == null ? openConnectionFromDbConn()
                : openConnectionFromCredential(serverURL, dbName, adminName, password);

        prepareStatements();
    }

    /**
     * Return a connecion by using dbconn.properties file
     *
     * @throws SQLException
     * @throws IOException
     */
    public static Connection openConnectionFromDbConn() throws SQLException, IOException {
        // Connect to the database with the provided connection configuration
        Properties configProps = new Properties();
        configProps.load(new FileInputStream("dbconn.properties"));
        String serverURL = configProps.getProperty("flightapp.server_url");
        String dbName = configProps.getProperty("flightapp.database_name");
        String adminName = configProps.getProperty("flightapp.username");
        String password = configProps.getProperty("flightapp.password");
        return openConnectionFromCredential(serverURL, dbName, adminName, password);
    }

    /**
     * Return a connecion by using the provided parameter.
     *
     * @param serverURL example: example.database.widows.net
     * @param dbName    database name
     * @param adminName username to login server
     * @param password  password to login server
     *
     * @throws SQLException
     */
    protected static Connection openConnectionFromCredential(String serverURL, String dbName,
                                                             String adminName, String password) throws SQLException {
        String connectionUrl =
                String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
                        dbName, adminName, password);
        Connection conn = DriverManager.getConnection(connectionUrl);

        // By default, automatically commit after each statement
        conn.setAutoCommit(true);

        // By default, set the transaction isolation level to serializable
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        return conn;
    }

    /**
     * Get underlying connection
     */
    public Connection getConnection() {
        return conn;
    }

    /**
     * Closes the application-to-database connection
     */
    public void closeConnection() throws SQLException {
        userIti.clear();
        loginStatus = false;
        conn.close();
        /*try {
            clear_search.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }*/
    }

    /**
     * Clear the data in any custom tables created.
     *
     * WARNING! Do not drop any tables and do not clear the flights table.
     */
    public void clearTables() {
        try {
            clearTables.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * prepare all the SQL statements in this method.
     */
    private void prepareStatements() throws SQLException {
        checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
        tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
        clearTables = conn.prepareStatement(CLEAR);
        create_user = conn.prepareStatement(CREATE_USER);
        find_user = conn.prepareStatement(FIND_USER);
        search_n_direct = conn.prepareStatement(SEARCH_N_DIRECT);
        search_indirect = conn.prepareStatement(SEARCH_INDIRECT);
        find_validreserve = conn.prepareStatement(FIND_VALIDRESERVE);
        find_cancel = conn.prepareStatement(FIND_CANCEL);
        find_reseveid = conn.prepareStatement(FIND_RESERVEID);
        add_reserve = conn.prepareStatement(ADD_RESERVE);
        cancel_reservation = conn.prepareStatement(CANCEL_RESERVATION);
        check_balance = conn.prepareStatement(CHECK_BALANCE);
        update_balance = conn.prepareStatement(UPDATE_BALANCE);
        update_paid = conn.prepareStatement(UPDATE_PAID);
        check_seats = conn.prepareStatement(CHECK_SEATS);
        update_seats = conn.prepareStatement(UPDATE_SEATS);
        insert_seats = conn.prepareStatement(INSERT_SEATS);
        all_reservation = conn.prepareStatement(All_RESERVATION);
        check_date = conn.prepareStatement(CHECK_DATE);
        find_flights = conn.prepareStatement(FIND_FLIGHTS);
    }

    /**
     * Takes a user's username and password and attempts to log the user in.
     *
     * @param username user's username
     * @param password user's password
     *
     * @return If someone has already logged in, then return "User already logged in\n" For all other
     *         errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
     */
    public String transaction_login(String username, String password) {
        try {
            if (loginStatus) {
                return "User already logged in\n";
            }
            username = username.toLowerCase();
            find_user.setString(1, username);
            ResultSet user = find_user.executeQuery();
            if (user.next()){
                byte[] salt = user.getBytes("salted");
                byte[] hashCorrect = user.getBytes("passwordHash");
                byte[] hash = generateHash(password, salt);
                if (Arrays.equals(hash, hashCorrect)) {
                    loginStatus = true;
                    logginUser = username;
                    return "Logged in as " + username +"\n";
                }
            }
            return "Login failed\n";
        } catch (SQLException e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            return errors.toString();
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implement the create user function.
     *
     * @param username   new user's username. User names are unique the system.
     * @param password   new user's password.
     * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
     *                   otherwise).
     *
     * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
     */
    public String transaction_createCustomer(String username, String password, int initAmount) {
        if (initAmount < 0) {
            return "Failed to create user\n";
        }
        username = username.toLowerCase();
        byte[] salt = generateSalt();
        byte[] hash = generateHash(password, salt);

        try {
            find_user.setString(1, username);
            ResultSet result = find_user.executeQuery();
            while (!result.next()) {
                conn.setAutoCommit(false);
                try{
                    create_user.clearParameters();
                    create_user.setString(1, username);
                    create_user.setBytes(2, hash);
                    create_user.setBytes(3, salt);
                    create_user.setInt(4, initAmount);
                    create_user.executeUpdate();
                    conn.commit();
                    conn.setAutoCommit(true);
                    return "Created user " + username + "\n";
                } catch(SQLException e) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    find_user.setString(1, username);
                    result = find_user.executeQuery();
                }
            }
            return "Failed to create user\n";
        } catch (SQLException exp) {
            String errmsg1 = exp.getMessage();
            return "Error" + errmsg1;
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implement the search function.
     *
     * Searches for flights from the given origin city to the given destination city, on the given day
     * of the month. If {@code directFlight} is true, it only searches for direct flights, otherwise
     * is searches for direct flights and flights with two "hops." Only searches for up to the number
     * of itineraries given by {@code numberOfItineraries}.
     *
     * The results are sorted based on total flight time.
     *
     * @param originCity
     * @param destinationCity
     * @param directFlight        if true, then only search for direct flights, otherwise include
     *                            indirect flights as well
     * @param dayOfMonth
     * @param numberOfItineraries number of itineraries to return
     *
     * @return If no itineraries were found, return "No flights match your selection\n". If an error
     *         occurs, then return "Failed to search\n".
     *
     *         Otherwise, the sorted itineraries printed in the following format:
     *
     *         Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
     *         minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
     *
     *         Each flight should be printed using the same format as in the {@code Flight} class.
     *         Itinerary numbers in each search should always start from 0 and increase by 1.
     *
     * @see Flight#toString()
     */
    public String transaction_search(String originCity, String destinationCity, boolean directFlight,
                                     int dayOfMonth, int numberOfItineraries) {

        StringBuffer sb = new StringBuffer();
        ArrayList<Itinerary> searchFlightsResults = new ArrayList<>();
        HashMap<Integer, Itinerary> itiInfo = new HashMap<>();
        searchFlightsResults.clear();
        try {
            searchAndStoreFlights(search_n_direct, searchFlightsResults, originCity, destinationCity, dayOfMonth, numberOfItineraries, true);
            if (!directFlight && searchFlightsResults.size() < numberOfItineraries) {
                searchAndStoreFlights(search_indirect, searchFlightsResults, originCity, destinationCity, dayOfMonth,
                        numberOfItineraries - searchFlightsResults.size(), false);
            }
            if (searchFlightsResults.isEmpty()) {
                return "No flights match your selection\n";
            }
            searchFlightsResults.sort(Itinerary::compareTo);
            for (int i = 0; i < searchFlightsResults.size(); i++) {
                sb.append(searchFlightsResults.get(i).toString(i));
                itiInfo.put(i, searchFlightsResults.get(i));
            }
            userIti.put(logginUser, itiInfo);
            return sb.toString();
        } catch (SQLException e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            return errors.toString();
        }
        finally {
            checkDanglingTransaction();
        }
    }

    private void searchAndStoreFlights(PreparedStatement searchStatement, ArrayList<Itinerary> searchFlightsResults, String originCity, String destCity, int dayOfMonth,
                                       int numberOfItineraries, boolean directFlight) throws SQLException {
        searchStatement.clearParameters();
        searchStatement.setInt(1, numberOfItineraries);
        searchStatement.setString(2, originCity);
        searchStatement.setString(3, destCity);
        searchStatement.setInt(4, dayOfMonth);
        ResultSet searchResult = searchStatement.executeQuery();
        while (searchResult.next()) {
            searchFlightsResults.add(getItinerary(searchResult, directFlight));
        }
    }

 /* fid, day_of_month, carrier_id, flight_num, " +
          "origin_city, dest_city, actual_time, " +
          "capacity, price " */

    private Flight getFlight(ResultSet resultSet, boolean directFlight) throws SQLException{
        Flight result = new Flight();
        int index = 1;
        if (!directFlight) {
            index = 10;
        }
        result.fid = resultSet.getInt(index);
        result.dayOfMonth = resultSet.getInt(index+1);
        result.carrierId = resultSet.getString(index+2);
        result.flightNum = resultSet.getString(index+3);
        result.originCity = resultSet.getString(index+4);
        result.destCity = resultSet.getString(index+5);
        result.time = resultSet.getInt(index+6);
        result.capacity = resultSet.getInt(index+7);
        result.price = resultSet.getInt(index+8);
        return result;
    }

    private Itinerary getItinerary(ResultSet resultSet, boolean directFlight) throws SQLException {
        Itinerary result = new Itinerary();
        result.firstFlight = getFlight(resultSet, true);
        if (!directFlight) {
            result.secondFlight = getFlight(resultSet, false);
        }
        return result;
    }


    /**
     * Implements the book itinerary function.
     *
     * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in
     *                    the current session.
     *
     * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
     *         If the user is trying to book an itinerary with an invalid ID or without having done a
     *         search, then return "No such itinerary {@code itineraryId}\n". If the user already has
     *         a reservation on the same day as the one that they are trying to book now, then return
     *         "You cannot book two flights in the same day\n". For all other errors, return "Booking
     *         failed\n".
     *
     *         And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n"
     *         where reservationId is a unique number in the reservation system that starts from 1 and
     *         increments by 1 each time a successful reservation is made by any user in the system.
     */
    public String transaction_book(int itineraryId) {
        if (!loginStatus) {
            return "Cannot book reservations, not logged in\n";
        }
        if(!userIti.containsKey(logginUser)){
            return "No such itinerary " + itineraryId + "\n";
        }
        //private static HashMap<String, HashMap<Integer, Itinerary>> userIti = new HashMap<>();
        HashMap<Integer, Itinerary> itiInfo = userIti.get(logginUser);
        if (!itiInfo.containsKey(itineraryId)) {
            return "No such itinerary " + itineraryId + "\n";
        }
        if (!itiInfo.get(itineraryId).hasCapacity()){
            return "Booking failed\n";
        }
        try {
            conn.setAutoCommit(false);

            ResultSet count = find_reseveid.executeQuery();
            int reserveid = 1;
            if (count.next()){
                reserveid = count.getInt("count") + 1;
            }

            Flight fid1 = itiInfo.get(itineraryId).firstFlight;
            check_seats.setInt(1, fid1.fid);
            ResultSet seats = check_seats.executeQuery();
            if (seats.next()){
                int remain = seats.getInt(1);
                if (remain > 0) {
                    update_seats.setInt(1, remain - 1);
                    update_seats.setInt(2, fid1.fid);
                    update_seats.executeUpdate();
                } else {
                    conn.setAutoCommit(true);
                    return "Booking failed\n";
                }
            } else {
                insert_seats.setInt(1, fid1.fid);
                insert_seats.setInt(2, fid1.capacity - 1);
                insert_seats.executeUpdate();
            }

            int dayOfMonth = fid1.dayOfMonth;
            check_date.setInt(1, dayOfMonth);
            check_date.setString(2, logginUser);
            ResultSet dateList = check_date.executeQuery();
            if (dateList.next()) {
                conn.setAutoCommit(true);
                return "You cannot book two flights in the same day\n";
            }

            int cost = itiInfo.get(itineraryId).totalCost();
            add_reserve.clearParameters();
            add_reserve.setInt(1, reserveid);
            add_reserve.setString(2, logginUser);
            add_reserve.setInt(3, itineraryId);
            add_reserve.setInt(5, fid1.fid);
            add_reserve.setInt(7, cost);
            add_reserve.setInt(10, dayOfMonth);

            // handle indirect flight
            int boo = 1;
            if (!itiInfo.get(itineraryId).isDirect()){
                boo = 0;
                Flight fid2 = itiInfo.get(itineraryId).secondFlight;
                check_seats.setInt(1, fid2.fid);
                ResultSet seats2 = check_seats.executeQuery();
                if (seats2.next()){
                    int remain = seats2.getInt(1);
                    if (remain > 0) {
                        update_seats.setInt(1, remain - 1);
                        update_seats.setInt(2, fid2.fid);
                        update_seats.executeUpdate();
                    } else {
                        conn.setAutoCommit(true);
                        return "Booking failed\n";
                    }
                } else {
                    insert_seats.setInt(1, fid2.fid);
                    insert_seats.setInt(2, fid2.capacity - 1);
                    insert_seats.executeUpdate();
                }
                add_reserve.setInt(6, fid2.fid);
            } else {
                add_reserve.setNull(6, Types.INTEGER);
            }

            add_reserve.setInt(4, boo);
            add_reserve.setInt(8, 0);
            add_reserve.setInt(9, 0);
            add_reserve.executeUpdate();
            conn.commit();
            conn.setAutoCommit(true);
            return "Booked flight(s), reservation ID: " + reserveid +"\n";
        } catch (SQLException e) {
            //String errmsg = e.getMessage();
            try {
                conn.rollback();
                conn.setAutoCommit(true);
                return transaction_book(itineraryId);
            } catch (SQLException exp) {
                String errmsg1 = exp.getMessage();
                return "Rollback failed" + errmsg1;
            }
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the pay function.
     *
     * @param reservationId the reservation to pay for.
     *
     * @return If no user has logged in, then return "Cannot pay, not logged in\n" If the reservation
     *         is not found / not under the logged in user's name, then return "Cannot find unpaid
     *         reservation [reservationId] under user: [username]\n" If the user does not have enough
     *         money in their account, then return "User has only [balance] in account but itinerary
     *         costs [cost]\n" For all other errors, return "Failed to pay for reservation
     *         [reservationId]\n"
     *
     *         If successful, return "Paid reservation: [reservationId] remaining balance:
     *         [balance]\n" where [balance] is the remaining balance in the user's account.
     */
    public String transaction_pay(int reservationId) {
        if (!loginStatus) {
            return "Cannot pay, not logged in\n";
        }
        try {
            conn.setAutoCommit(false);
            // check if reservation is valid
            find_validreserve.setString(1, logginUser);
            find_validreserve.setInt(2, reservationId);
            ResultSet result1 = find_validreserve.executeQuery();
            int cost = 0;
            if (result1.next()) {
                cost = result1.getInt("cost");
            } else {
                conn.setAutoCommit(true);
                return "Cannot find unpaid reservation " +  reservationId + " under user: " + logginUser +"\n";
            }

            // check if contain enough balance
            check_balance.setString(1, logginUser);
            ResultSet result2 = check_balance.executeQuery();
            int balance = 0;
            if (result2.next()) {
                balance = result2.getInt(1);
            }
            if (balance < cost) {
                conn.setAutoCommit(true);
                return "User has only " + balance + " in account but itinerary costs " + cost + "\n";
            }

            // update balance
            int remain = balance - cost;
            update_balance.setInt(1, remain);
            update_balance.setString(2, logginUser);
            update_balance.executeUpdate();

            //update reservation
            update_paid.setInt(1, reservationId);
            update_paid.executeUpdate();

            conn.commit();
            conn.setAutoCommit(true);
            return "Paid reservation: " + reservationId + " remaining balance: " + remain + "\n";
        } catch (SQLException e) {
            String errmsg = e.getMessage();
            try {
                conn.rollback();
                conn.setAutoCommit(true);
                return "Failed to pay for reservation " + reservationId + "\n";
            } catch (SQLException exp) {
                String errmsg1 = exp.getMessage();
                return "Rollback failed" + errmsg1;
            }
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the reservations function.
     *
     * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
     *         the user has no reservations, then return "No reservations found\n" For all other
     *         errors, return "Failed to retrieve reservations\n"
     *
     *         Otherwise return the reservations in the following format:
     *
     *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
     *         reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
     *         [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
     *         reservation]\n ...
     *
     *         Each flight should be printed using the same format as in the {@code Flight} class.
     *
     * @see Flight#toString()
     */
    public String transaction_reservations() {
        if (!loginStatus) {
            return "Cannot view reservations, not logged in\n";
        }
        StringBuffer sb = new StringBuffer();
        try {
            //conn.setAutoCommit(false);
            all_reservation.setString(1, logginUser);
            ResultSet result = all_reservation.executeQuery();
            if (!result.isBeforeFirst()) {
                //conn.setAutoCommit(true);
                return "No reservations found\n";
            }
            while (result.next()) {
                sb.append("Reservation ").append(result.getInt("reserveID")).append(" paid: ");
                if (result.getInt("ifpaid") == 1) {
                    sb.append("true:\n");
                } else {
                    sb.append("false:\n");
                }
                int fid1 = result.getInt("fid1");
                find_flights.setInt(1, fid1);
                ResultSet flights = find_flights.executeQuery();
                if(flights.next()){
                    Flight first = new Flight();
                    first.fid = fid1;
                    first.dayOfMonth = flights.getInt("day_of_month");
                    first.carrierId = flights.getString("carrier_id");
                    first.flightNum = String.valueOf(flights.getInt("flight_num"));
                    first.originCity = flights.getString("origin_city");
                    first.destCity = flights.getString("dest_city");
                    first.time = flights.getInt("actual_time");
                    first.capacity = flights.getInt("capacity");
                    first.price = flights.getInt("price");
                    sb.append(first.toString());
                }
                if (result.getInt("ifdirect") == 0){
                    int fid2 = result.getInt("fid2");
                    find_flights.setInt(1, fid2);
                    ResultSet flights2 = find_flights.executeQuery();
                    if (flights2.next()){
                        Flight second = new Flight();
                        second.fid = fid2;
                        second.dayOfMonth = flights.getInt("day_of_month");
                        second.carrierId = flights.getString("carrier_id");
                        second.flightNum = String.valueOf(flights.getInt("flight_num"));
                        second.originCity = flights.getString("origin_city");
                        second.destCity = flights.getString("dest_city");
                        second.time = flights.getInt("actual_time");
                        second.capacity = flights.getInt("capacity");
                        second.price = flights.getInt("price");
                        sb.append(second.toString());
                    }
                }
            }
            //conn.commit();
            //conn.setAutoCommit(true);
            return sb.toString();
        } catch (SQLException e){
            /*try {
                conn.rollback();
                conn.setAutoCommit(true);
                return transaction_reservations();
            } catch (SQLException exp) {
                String errmsg1 = exp.getMessage();
                return "Rollback failed" + errmsg1;
            }*/
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            return errors.toString();
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Implements the cancel operation.
     *
     * @param reservationId the reservation ID to cancel
     *
     * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n" For
     *         all other errors, return "Failed to cancel reservation [reservationId]\n"
     *
     *         If successful, return "Canceled reservation [reservationId]\n"
     *
     *         Even though a reservation has been canceled, its ID should not be reused by the system.
     */
    public String transaction_cancel(int reservationId) {
        if (!loginStatus) {
            return "Cannot cancel reservations, not logged in\n";
        }
        try {
            conn.setAutoCommit(false);
            find_cancel.setString(1, logginUser);
            find_cancel.setInt(2, reservationId);
            ResultSet result = find_cancel.executeQuery();
            if (!result.next()){
                conn.setAutoCommit(true);
                return "Failed to cancel reservation " + reservationId + "\n";
            } else {
                int ifpaid = result.getInt(8);
                if (ifpaid == 1) {
                    check_balance.setString(1, logginUser);
                    ResultSet result2 = check_balance.executeQuery();
                    if (result2.next()) {
                        int balance = result2.getInt(1);
                        int cost = result.getInt(7);
                        int refund = balance + cost;
                        update_balance.setInt(1, refund);
                        update_balance.setString(2, logginUser);
                        update_balance.executeUpdate();
                    }
                }
                int fid1 = result.getInt("fid1");
                check_seats.setInt(1, fid1);
                ResultSet seats = check_seats.executeQuery();
                if (seats.next()){
                    int remain = seats.getInt(1);
                    update_seats.setInt(1, remain + 1);
                    update_seats.setInt(2, fid1);
                    update_seats.executeUpdate();
                }

                int ifdirect = result.getInt("ifdirect");
                if (ifdirect == 0){
                    int fid2 = result.getInt("fid2");
                    check_seats.setInt(1, fid2);
                    ResultSet seats2 = check_seats.executeQuery();
                    if (seats2.next()){
                        int remain = seats2.getInt(1);
                        update_seats.setInt(1, remain + 1);
                        update_seats.setInt(2, fid2);
                        update_seats.executeUpdate();
                    }
                }

                cancel_reservation.clearParameters();
                cancel_reservation.setInt(1, reservationId);
                cancel_reservation.executeUpdate();
                conn.commit();
                conn.setAutoCommit(true);
                return "Canceled reservation "+ reservationId + "\n";
            }
        } catch (SQLException e) {
            String errmsg = e.getMessage();
            try {
                conn.rollback();
                conn.setAutoCommit(true);
                return "Failed to cancel reservation " + reservationId + "\n";
            } catch (SQLException exp) {
                String errmsg1 = exp.getMessage();
                return "Rollback failed" + errmsg1;
            }
        } finally {
            checkDanglingTransaction();
        }
    }

    /**
     * Example utility function that uses prepared statements
     */
    private int checkFlightCapacity(int fid) throws SQLException {
        checkFlightCapacityStatement.clearParameters();
        checkFlightCapacityStatement.setInt(1, fid);
        ResultSet results = checkFlightCapacityStatement.executeQuery();
        results.next();
        int capacity = results.getInt("capacity");
        results.close();

        return capacity;
    }

    /**
     * Throw IllegalStateException if transaction not completely complete, rollback.
     *
     */
    private void checkDanglingTransaction() {
        try {
            try (ResultSet rs = tranCountStatement.executeQuery()) {
                rs.next();
                int count = rs.getInt("tran_count");
                if (count > 0) {
                    throw new IllegalStateException(
                            "Transaction not fully commit/rollback. Number of transaction in process: " + count);
                }
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error", e);
        }
    }

    private static boolean isDeadLock(SQLException ex) {
        return ex.getErrorCode() == 1205;
    }

    // Generate the hash
    private byte[] generateHash(String password, byte[] salt){
        // Specify the hash parameters
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);
        SecretKeyFactory factory = null;
        byte[] hash = null;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return hash = factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException();
        }
    }

    private byte[] generateSalt() {
        // Generate a random cryptographic salt
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    class Itinerary implements Comparable<Itinerary> {
        Flight firstFlight;
        Flight secondFlight;
        int itineraryID;

        boolean isDirect() {
            return secondFlight == null;
        }

        int totalTime() {
            if (secondFlight != null) {
                return secondFlight.time + firstFlight.time;
            } else {
                return firstFlight.time;
            }
        }

        int totalCost() {
            if (secondFlight != null) {
                return secondFlight.price + firstFlight.price;
            } else {
                return firstFlight.price;
            }
        }

        boolean hasCapacity() {
            if (secondFlight == null) {
                return firstFlight.capacity > 0;
            } else {
                return firstFlight.capacity > 0 && secondFlight.capacity > 0;
            }
        }

        String toString(int num) {
            itineraryID = num;
            StringBuilder result = new StringBuilder();
            result.append("Itinerary ").append(num).append(": ");
            if (this.isDirect()) {
                result.append(1);
            } else {
                result.append(2);
            }
            result.append(" flight(s), ").append(this.totalTime()).append(" minutes\n");
            result.append(firstFlight);
            if (!this.isDirect()) {
                result.append(secondFlight);
            }
            return result.toString();
        }


        @Override
        public int compareTo(Itinerary other) {
            int result = Integer.compare(this.totalTime(), other.totalTime());

            if (result == 0) {
                if (this.isDirect()) {
                    // use fid to break the tie
                    if (other.isDirect()) {
                        return Integer.compare(this.firstFlight.fid, other.firstFlight.fid);
                    } else {
                        result = Integer.compare(this.firstFlight.fid, other.secondFlight.fid);
                        if (result != 0) {
                            return result;
                        } else {
                            return -1;
                        }
                    }
                } else {
                    if (other.isDirect()) {
                        result = Integer.compare(this.secondFlight.fid, other.firstFlight.fid);
                        if (result != 0) {
                            return result;
                        } else {
                            return 1;
                        }
                    } else {
                        result = Integer.compare(this.secondFlight.fid, other.secondFlight.fid);
                        if (result != 0) {
                            return result;
                        } else {
                            return Integer.compare(this.firstFlight.fid, other.firstFlight.fid);
                        }
                    }
                }
            } else {
                return result;
            }
        }
    }



    /**
     * A class to store flight information.
     */
    class Flight {
        public int fid;
        public int dayOfMonth;
        public String carrierId;
        public String flightNum;
        public String originCity;
        public String destCity;
        public int time;
        public int capacity;
        public int price;

        @Override
        public String toString() {
            return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
                    + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
                    + " Capacity: " + capacity + " Price: " + price + "\n";
        }
    }
}

