package app;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Created by Tomasz Guzik on 2017-03-10.
 * TCP and UDP channels handler.
 */
public class ChatServer {
    public static boolean DEBUG = true;

    private static final int PORT = 9999;
    private final int PACKET_SIZE = 10000;
    private volatile boolean isShutDown = false;
    private boolean multicast = false;
    private final String multicastAddress = "228.5.6.7";
    private InetAddress multicastGroup = InetAddress.getByName(multicastAddress);

    private ServerSocket serverSocket;
    private List<ClientHandler> clients;

    public ChatServer() throws IOException {
        clients = new LinkedList<>();
        serverSocket = new ServerSocket(PORT);
    }

    public ChatServer(boolean multicast) throws IOException {
        this.multicast = multicast;
        clients = new LinkedList<>();
        serverSocket = new ServerSocket(PORT);
    }

    public static void main(String []argv) throws IOException {
        /** Multicast or not */
        ChatServer chatServer = new ChatServer(true);
        chatServer.runServer();
    }

    private void runServer() {
        if (DEBUG) log("Server running...");

        /** Run UDP channel */
        runUDP();

        /** Run TCP channel */
        runTCP();
    }

    /**
     * TCP handler.
     */
    private void runTCP() {
        if (DEBUG) log("TCP ready!");
        while(true){
            try {
                Socket newClient = serverSocket.accept();
                String hostName = newClient.getInetAddress().getHostAddress();
                if (DEBUG) log("New user from " + hostName + ":" + newClient.getPort());

                ClientHandler clientHandler = new ClientHandler(newClient, hostName);
                synchronized (clients) { clients.add(clientHandler); }

                clientHandler.start();
                clientHandler.send("M", "Welcome to C.H.A.T. !");
            } catch (IOException e){
                log("IO Exception on server: " + e);
            }
        }
    }

    /**
     * UDP handler.
     */
    private void runUDP() {
        new Thread(new Runnable() {
            public void run() {
                if (DEBUG)
                    if(multicast) log("UDP multicast ready!");
                    else log("UDP ready!");

                byte buffer[] = new byte[PACKET_SIZE];
                try (DatagramSocket serverSocketUDP = new DatagramSocket(PORT)) {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    while (!isShutDown) {
                        serverSocketUDP.receive(request);
                        if (DEBUG) log("UDP request from " + request.getAddress() + ":" + request.getPort());
                        if(multicast)
                            multicastUDP(serverSocketUDP, request);
                        else
                            broadcastUDP(serverSocketUDP, request);
                    }
                } catch (IOException e) {
                    log("IOException " + e);
                }
            }
        }).start();
    }

    /**
     * Sending UDP message to chat clients.
     * @param serverSocketUDP - server DatagramSocket
     * @param request - message from sender
     */
    private void broadcastUDP(DatagramSocket serverSocketUDP, DatagramPacket request) {
        clients.forEach(c -> {
            /** Should be:
             *  if( c.clientSocket.getInetAdress ... )
             *  Checking ports because of local use. Address is the same.
             *  */
            if (c.clientSocket.getPort() != request.getPort()) {
                try {
                    DatagramPacket response = new DatagramPacket(request.getData(),
                        request.getLength(), c.clientSocket.getInetAddress(), c.clientSocket.getPort());

                    serverSocketUDP.send(response);
                    if (DEBUG) log("UDP response to " + c.clientSocket.getInetAddress() + ":" + c.clientSocket.getPort());
                } catch (IOException e) {
                    log("IOException " + e);
                }
            }
        });
    }

    /**
     * Sending UDP multicast message to chat clients who belong to group.
     * @param serverMulticastSocketUDP - server DatagramSocket
     * @param request - message from sender
     */
    private void multicastUDP(DatagramSocket serverMulticastSocketUDP, DatagramPacket request){
        try {
            DatagramPacket response = new DatagramPacket(request.getData(), request.getLength(), multicastGroup, PORT-1);
            serverMulticastSocketUDP.send(response);
            if (DEBUG) log("UDP response to " + multicastGroup + ":" + (PORT-1));
        } catch (IOException e) {
            log("IOException " + e);
        }
    }

    private static void log(String s) {
        System.out.println(s);
    }

    /**
     * TCP channel handler for single chat client. Running in a new thread.
     * ClientHandler objects are stored in "clients" LinkedList.
     * Adding and removing ClientHandler to list are synchronized operations.
     */
    private class ClientHandler extends Thread {
        private Socket clientSocket;
        private BufferedReader inputStream;
        private PrintWriter outputStream;
        private String clientIP;
        private String login;

        private ClientHandler(Socket socket, String ip) throws IOException{
            this.clientSocket = socket;
            this.clientIP = ip;
            this.inputStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.outputStream = new PrintWriter(socket.getOutputStream(), true);
        }

        @Override
        public void run() {
            String msg;
            char option; // L = login, M = msg, Q = quit
            try {
                while( (msg = inputStream.readLine()) != null){
                    /** First char in every single message is an option.*/
                    option = msg.charAt(0);
                    msg = msg.substring(1);
                    if (DEBUG) log("TCP message received, size=" + msg.length());

                    /** There is several message types:
                     *  M - text message
                     *  L - logging in
                     *  Q - quit, logging out
                     *  */
                    switch(option){
                        case 'M':
                            if (login != null)
                                broadcast(login, msg, "M");
                            break;

                        case 'L':
                            /** Assume login is valid. */
                            login = msg;
                            broadcast(login, " has just been logged in. Hello!", "L");
                            broadcast("", Integer.toString(clients.size()), "S");
                            break;

                        case 'Q':
                            broadcast(login, " has just been logged out. Bye!", "Q");
                            broadcast("", Integer.toString(clients.size()-1), "S");
                            close();
                            return;
                    }
                }
            } catch(IOException e){
                log("IO Exception on client thread: " + e);
            } finally {
                synchronized (clients){
                    clients.remove(this);
                }
            }
        }

        /**
         * TCP socket closing after logout.
         */
        private void close() {
            if (clientSocket == null) {
                log("Socket has not been opened.");
                return;
            }
            try {
                log("User from " + clientSocket.getInetAddress().getHostName() + ":" + clientSocket.getPort() + " has been disconnected.");
                clientSocket.close();
                clientSocket = null;
            } catch (IOException e) {
                log("Failure during close to " + clientIP);
            }
        }

        /**
         * Sending messages to all chat clients.
         * @param senderLogin
         * @param msg
         * @param option
         */
        private void broadcast(String senderLogin, String msg, String option) {
            clients.forEach(c -> {
                if (!c.login.equals(senderLogin)){
                    if(option.equals("M"))
                        c.send("M" + senderLogin + ": ", msg);
                    else if(option.equals("S"))
                        c.send("S" + senderLogin, msg);
                    else
                        c.send("M" + senderLogin, msg);
                }
            });
        }

        /**
         * Sending message to single chat client.
         * @param senderLogin
         * @param msg
         */
        private void send(String senderLogin, String msg) {
            outputStream.println(senderLogin + msg);
            if (DEBUG) log("TCP message sent, size=" + (msg.length() + senderLogin.length()));
        }
    }
}
