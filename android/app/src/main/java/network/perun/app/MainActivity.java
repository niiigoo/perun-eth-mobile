package network.perun.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.Arrays;

import prnm.*;

interface ChannelAcceptor {
    public void accept(PaymentChannel channel);
}

class ProposalHandler implements prnm.ProposalHandler {
    // acceptor.accept will be called on every new incomming channel
    ChannelAcceptor acceptor;

    public ProposalHandler(ChannelAcceptor acc) {
        acceptor = acc;
    }

    public void handle(ChannelProposal proposal, ProposalResponder responder) {
        Context ctx = Prnm.contextWithTimeout(5);
        try {
            BigInts bals = proposal.getInitBals();
            Log.i("prnm", String.format("Channel proposal with %s: %d:%d", proposal.getPeerPerunID().toHex(), bals.get(0).toInt64(), bals.get(1).toInt64()));
            acceptor.accept(responder.accept(ctx));
        } catch (Exception e) {
            Log.e("prnm", e.toString());
        } finally {
            ctx.cancel();
        }
    }
}

class UpdateHandler implements prnm.UpdateHandler {
    PaymentChannel ch;

    public UpdateHandler(PaymentChannel channel) {
        ch = channel;
    }

    public void handle(ChannelUpdate update, UpdateResponder responder) {
        Context ctx = Prnm.contextWithTimeout(5);
        try {
            State state = update.getState();
            Log.i("channel", String.format("Update (version=%d, isFinal=%b)", state.getVersion(), state.isFinal()));

        } catch (Exception e) {
            Log.e("channel", e.toString());
        } finally {
            ctx.cancel();
        }
    }
}

class Node implements ChannelAcceptor {
    Client client;
    String appDir;
    Address myOnChain;
    ProposalHandler propHandler;

    public Node(Config cfg, Wallet wallet) throws Exception {
        // Possibly has to deploy contracts, so give it some extra time.
        Context ctx = Prnm.contextWithTimeout(30);
        try {
            client = new Client(ctx, cfg, wallet);
        } finally {
            ctx.cancel();
        }
        propHandler = new ProposalHandler(this);

        // Start a new thread for handling channel proposals
        new Thread(() -> {
            client.handleChannelProposals(propHandler);
        }).start();
    }

    public void propose(Address peer, BigInts initBals, String ip, int port) throws Exception {
        // This is safe to call more than once.
        client.addPeer(peer, ip, port);
        // Has to send transactions, so give it some extra time.
        Context ctx = Prnm.contextWithTimeout(30);
        try {
            accept(client.proposeChannel(ctx, peer, 300, initBals));
        } finally {
            ctx.cancel();
        }
    }

    @Override
    public void accept(PaymentChannel channel) {
        Log.i("prnm", "New channel " +channel.getIdx());
        // Start a new thread for watching the channel
        new Thread(() -> {
            try {
                Log.d("channel", "Starting watching");
                channel.watch();
                Log.d("channel", "Stopped watching");
            }  catch (Exception e) {
                Log.e("channel", "Error watching:" + e.toString());
            }
        }).start();

        // Start a new thread for handling channel updates
        UpdateHandler updateHandler = new UpdateHandler(channel);
        new Thread(() -> {
            Log.d("channel", "Starting handling updates");
            channel.handleUpdates(updateHandler);
            Log.d("channel", "Stopped handling updates");
        }).start();
    }
}

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Prnm.setLogLevel(6);
            Thread.sleep(2000);
            // Get the Apps data directory.
            String appDir = getApplicationContext().getFilesDir().getAbsolutePath();
            String ksPath = appDir +"/keystore";
            String dbPath = appDir +"/database";
            String password = "5gwXKA7lBTJ6+S8ozZ983w";
            String sk = "0x69cb97043e56883d66627e8f7a828877a56022d0fb05ae6197e6e16fb56282d0";

            // Create a wallet.
            Wallet wallet = Prnm.newWallet(ksPath, password);
            // We are alice in this example and this is our on-chain secret key holding the ETH.
            Address myOnChain = wallet.importAccount(sk);
            Log.i("prnm", "Address: " +myOnChain.toHex());
            // 10.0.2.2 is the IP of the host PC when using Android Simulator and the host is running a ganache-cli.
            // 8545 is the standart port of ganache-cli.
            String ethUrl = "ws://10.0.2.2:8545";

            // Using null as either Adjudicator or Assetholder tells the Client to deploy the contracts,
            // in this case we already deployed them.
            Address adjudicator = new Address("0xDc4A7e107aD6dBDA1870df34d70B51796BBd1335");
            Address assetholder = new Address("0xb051EAD0C6CC2f568166F8fEC4f07511B88678bA");

            Config cfg = new Config("Alice ", myOnChain, adjudicator, assetholder, dbPath, ethUrl, "127.0.0.1", 5750);
            Node node = new Node(cfg, wallet);

            // PerunId (currently an Address) of the peer that we want to open a channel with.
            Address bob = new Address("0xA298Fc05bccff341f340a11FffA30567a00e651f");
            // Create the initial balances of the channel, me starting with 2000 and bob with 1000.
            BigInts initBals = Prnm.newBalances(new BigInt(2000), new BigInt(1000));
            // Propose a channel to bob.
            node.propose(bob, initBals, "10.0.2.2", 5750);
        } catch (Exception e) {
            Log.e("prnm", e.toString());
        }
    }
}