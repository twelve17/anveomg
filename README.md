# AnveOMG

SMS message store and UI for use with anveo.com's SMS gateway.

This is a little [compojure](https://github.com/weavejester/compojure) web app that interfaces with [anveo.com](http://www.anveo.com)'s SMS gateway.  It saves SMS messages sent by the gateway and provides a little UI that lets you view locally stored messages and reply to messages (sending them back to anveo).  It's my fun project to learn clojure with, so be warned, the clojure in here probably sucks.

*Note: The author of this software is in no way affiliated with anveo.com.*

## Anveo Setup

As of this writing, the free tier subscription level provides the functionality required to integrate with AnveOMG.

Assuming you have a Anveo account and a phone number, do the following:

- Set up a "Call Flow API Key" by loggin into your account and going to the [API link at the bottom of the page](https://www.anveo.com/api.asp).  If you have not done this already, you will see an option to create an `API UserKey` under the `API Configuration` section on the right.  You wil need this key later.

- Go to the ["Phone Numbers" -> "Manage Phone Number"](https://www.anveo.com/phonenumbers.asp) page.  Click on "Edit" for the phone number you wish to integrate with AnveOMG.  Go to the SMS options tab.  Under "SMS Forwarding Options", check the box for "Forward to URL:".  Assuming you have set up AnveOMG on a host named `www.example.com` running on port `8000`, set the URL value to: `http://www.example.com:8000/api/message?from=$[from]$&to=$[to]$&message=$[message]$`.  Note that this Anveo feature does not seem to support self-signed SSL certs, so you will need to use a http URL if you do not have a self-signed SSL associated with your server running AnveOMG.


## AnveOMG Setup

### Dependencies

- MySQL (I know.) Tested with:
  `Ver 14.14 Distrib 5.6.24, for osx10.10 (x86_64)`
- Leinengen.  Tested with:
  `Leiningen 2.5.1 on Java 1.7.0_75 Java HotSpot(TM) 64-Bit Server VM`

### Installation - Dev

Somewhere on your server, clone this repo, then:

    # lein ring server

### Installation - Prod

Somewhere on your server, clone this repo.


#### Create the Database

There is a schema SQL file: `etc/schema.sql`.  Create a user in mysql, grant it privileges, and then use the `mysql` command to import it, e.g:

    # mysql -u my-user -p my-db < etc/schema.sql 

#### Create a AnveOMG Config

Copy the supplied template and fill in values as necessary.

    # cp etc/config.edn.template  etc/config.edn
    
Update the values in the `:db` and `:anveo` as appropriate.  Set the `:call-flow-api-key` to the key generated in the "Anveo Setup" section.  Chances are that the `:post-message-url` is correct, but you can verify this in anveo.com's [Send SMS using HTTP Gateway](http://www.anveo.com/api.asp?code=apihelp_sms_send_http&api_type=) page.

Finally, set the `:mock-send-mode` to `false`.  (This is a mode used for testing.  Instead of sending a message to anveo.com, it saves it locally, and also creates a mock response to the message one second later.)

#### Running the Server

Because I am a clojure noob, I am running the app with `leinengen` in a GNU screen.  (I'm looking forward to replacing this with [an nginx setup at some point](https://fitacular.com/blog/clojure/2014/07/14/deploy-clojure-tomcat-nginx/)).  In the meantime, you can run the server the standard `ring` way:

    # lein ring server

It will start the server on `localhost:3000` by default.  However, there are some complications.  Keep reading.

#### Configure Firewall and Reverse Proxy

AnveOMG is just a little baby right now.  It doesn't yet have authentication built in.  

In addition, anveo.com's [Forward to URL](http://www.anveo.com/api.asp?code=apihelp_sms_receive_http&api_type=) feature works by issuing a `GET` request at the given URL.  This is pretty bad.  `GET` requests [are not meant to be updating resource related data on the target resource](http://programmers.stackexchange.com/questions/188860/why-shouldnt-a-get-request-change-data-on-the-server).  In addition, `Forward to URL` cannot target a server running a self-signed SSL certificate (a theory somewhat ambiguously confirmed by anveo.com staff in a support ticket).  Also `http://user:pass@example.com` type URLs are not supported.  

So, we need to mitigate all these shortcomings as best as possible.  One way to do this is to run a web server that listens on two ports.  One port will listen for the "incomging SMS message" unauthenticated `GET` request from anveo.com (let's call this the `API port`). You can set up a firewall rule to only allow requests to the `API port` from anveo.com's IP address, otherwise you might end up with a [bot mucking up your data](http://thedailywtf.com/articles/WellIntentioned-Destruction). 

Anyway, the second port will respond to HTTPS requests for the AnveOMG UI, and can be secured with basic auth (I KNOW, it's on the TODO list!).  Let's call this one the `UI port`.

AnveOMG's URL paths are configured to keep these two concerns segregated:  UI paths begin with `/web`, and the API path begins with `/api`.

So, assuming UI port 9001 and API port 9002:

- The firewall configuration might be along these lines:

```
    # Allow users to access the AnveOMG HTTPS UI
    iptables -A INPUT -p tcp --dport 9001 -j ACCEPT

    # Allow only Anveo to submit messages to AnveOMG
    iptables -A INPUT -p tcp -s 1.2.3.4 --dport 9002 -j ACCEPT
```

Change `1.2.3.4` to Anveo's IP address (as of this writing, it is `74.86.96.2`, but you should confirm this by looking at the access logs prior to hardening).

- The Apache configuration might look like this (these are incomplete!):
  - ports.conf:
  
    ```
    ServerName foo.example.com
    # API Port
    Listen 9002

    <IfModule mod_ssl.c>
    # UI Port
    Listen 9001
    </IfModule>
    ```
  
  - UI port config (HTTPS, with auth):

    ```
    <IfModule mod_ssl.c>
    <VirtualHost _default_:9001>
        ServerAdmin foo@example.com
        ServerName sms.example.com

        RedirectMatch "^/$" "/web/messages/thread-summary"
        ProxyPass /web http://localhost:3000/web
        ProxyPassReverse /web http://localhost:3000/web
        ProxyPass /assets http://localhost:3000/assets
        ProxyPassReverse /assets http://localhost:3000/assets

        <Location /web/>
            AuthType basic
            AuthName "private area"
            AuthBasicProvider dbm
            AuthDBMType default
            AuthDBMUserFile /path/to/your/dbmpasswd.dat
            Require valid-user
        </Location>

        ErrorLog ${APACHE_LOG_DIR}/anveomg-ui-ssl-error.log
        LogLevel warn
        LogFormat "%V %h %l %u %t \"%r\" %s %b" vcommon
        CustomLog ${APACHE_LOG_DIR}/access.log vcommon 
      </VirtualHost>

    ```

  - API port config (HTTP):

    ```
    <VirtualHost *:9002>
        ServerAdmin foo@example.com
        ServerName sms.example.com

        ProxyPass /api http://localhost:3000/api
        ProxyPassReverse /api http://localhost:3000/api

        ErrorLog ${APACHE_LOG_DIR}/error.log
        LogLevel warn
        LogFormat "%V %h %l %u %t \"%r\" %s %b" vcommon
        CustomLog ${APACHE_LOG_DIR}/access.log vcommon
    </VirtualHost>
    ```

## TODO

In no particular order...

- Legit auth implementation
- User friendly timestamps
- Fancy AJAX UI
- NGINX integration for prod/proper deployment
- Google Contacts API integration