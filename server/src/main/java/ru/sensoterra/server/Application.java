package ru.sensoterra.server;

import io.micronaut.runtime.Micronaut;
import ru.sensoterra.server.cli.AdminCli;

public class Application {
    public static void main(String[] args) {
        // `sensoterra-server admin ...` manages admin accounts and exits; no server is started.
        if (args.length > 0 && AdminCli.COMMAND.equals(args[0])) {
            AdminCli.main(args);
            return;
        }
        Micronaut.run(Application.class, args);
    }
}
