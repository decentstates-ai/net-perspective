# net-perspective: scaling human relations

A distributed trust-network for information.

Users are public-key pairs, no need to verify their identity.

Users relate to other users or uris under contexts (like a file path,) and
specify the depth of the relation in the case of users. You can add some small
metadata such as a name and description.

Then you can collect all the users and uris under a context, you have a list of
users and items you "relate" to, along with metadata like names and
descriptions and why you relate to them (who the last link in the chain is.)

This this results in emergent curation of information.


What's the jist of this tech-wise? Signed documents of direct-relations,
submitted to and hosted by peers, DHT as a directory to find users' data, every
ten minutes peers servers calculate data dependency sets inductively from
other direct relation dependency sets, you seed data you cache, popular data
will have more seeders. Peers keep your dependant direct-relation documents
cached and quickly available. When you relate to someone you can see the size
of their contexts, you can set limits to exclude contexts if their size grows
too fast. You get warnings next to entries from old direct-relations documents
(when a user hasn't updated theirs in a long time.)


An oversimplified explanation: It is a tool for making lists from other peoples lists.

Examples:
- Relating to users food recommendations.
- Relating to users for specific news, local or international.
- Collecting RSS feeds for specific categories and exporting to your news feed.
- Collecting GitHub accounts for use in [vouch]()
- Creating a map of local places.

Features:
- Local and transportable identity - your key-pair is local and you can easily
  switch which peer server you use or have multiple peers.
- Custom depth - you specify how many layers deep you trust someone
  transitively.
- Context mapping - your contexts you expose don't have to contain relations to
  other people of the same context.
- Context globbing - you can include someone elses subcategories under your
  categories, or you can collapse their subcategories into one category.


## Documents:

All documents are encoded RFC 8785 JSON.

### env (envelope)

- env/content: arbitrary json
- env/user-id: multiformats/multihash of multiformats/multicodec encoded public key, ml-dsa-44
- env/user-public-key: multiformats/multicodec encoded public key, ml-dsa-44
- env/signature: string of signature signing the content

### user (user-info)

Within a signed envelope:

- user/version: integer
- user/timestamp-ns: unix timestamp in ns
- user/user-id: multiformats/multihash of multiformats/multicodec encoded public key, ml-dsa-44
- user/user-public-key: multiformats/multicodec encoded public key, ml-dsa-44
- user/dr-address: content address of the user's direct-relations document
- user/trusted-peers: array of user-ids the user trusts to peer them.
- user/peered-users: map of user-id ->
  - user-peered/user-id
  - user-peered/crd-idx-address


### dr (direct-relations)

(should this be merged into the above)

Fields:
- dr/version:
- dr/timestamp-ns: unix timestamp in ns
- dr/user-id: multiformats/multihash of multiformats/multicodec encoded public key, ml-dsa-44

- dr/contact-email: (optional) valid email
- dr/contact-signal-username: (optional) valid signal id
- dr/contact-number: (optional) valid international mobile number

- dr/contexts: array of:
  - dr-ctx/path: array of strings matching `/[a-z][a-z0-9\-]+/`
  - dr-ctx/relations: array of:
    - dr-rel/type: one of "user" or "uri"
    - dr-rel-uri/uri: uri field
    - dr-rel-uri/name: optional, string.
    - dr-rel-uri/comment: optional string.
    - dr-rel-user/user-id: same as dr/user-id
    - dr-rel-user/context-path: optional, defaults to nil which means it is the same as the context path in dr-ctx/path. Same type as dr-ctx/path.
    - dr-rel-user/transitive-depth: optional, defaults to 2, max 10.
    - dr-rel-user/subject-glob: optional, defaults to 0 (no glob), integer 0-10. When non-zero, the subject's context path is treated as a prefix: Alice's context `food` with subject-glob 1 matches Bob's contexts `food.*` up to 1 level deep, expanding Alice's view to include those subcontexts.
    - dr-rel-user/object-glob: optional, defaults to 0 (no glob), integer 0-10. When non-zero, all of the object's matching subcontexts are collapsed into the subject's context: Alice's context `food` with object-glob 1 collects everything Bob exposes under `food.*` into Alice's single `food` context.


### crd (context-relations-deps)

Produced by a peer.

Produced inductively from other users' crd documents.

Fields:
- crd/version: integer
- crd/timestamp-ns
- crd/user-id: same as dr/user-id
- crd/context-path: same as dr-ctx/path
- crd/hops: array of:
  - crd-hop/hop: integer 1-10
  - crd-hop/dr-addresses: array of multiformats/multihash — content addresses of the individual dr documents at this hop
  - crd-hop/archive-address: multiformats/multihash — content address of a compressed archive containing all dr documents at this hop
  - crd-hop/size: size in bytes of all the content in this hop
- crd/source-addresses: array of multiformats/multihash


### crd-idx (context-relations-deps-index)

Produced by a peer.

Fields:
- crd-idx/version: integer
- crd-idx/timestamp-ns
- crd-idx/user-id: same as dr/user-id
- crd-idx/contexts: array of:
  - crd-idx-ctx/path: same as dr-ctx/path
  - crd-idx-ctx/crd-address: multiformats/multihash — content address of the crd document
  - crd-idx-ctx/hops: integer 1-10
  - crd-idx-ctx-hop/dr-addresses: array of multiformats/multihash
  - crd-idx-ctx-hop/size: size in bytes of all the dr documents in this hop
  - crd-idx-ctx/size: integer, bytes.


## Mechanics

The initial implementation with use IPFS as the store and IPNS as the directory for user-ids.

### Initialisation

A user has a user-client, which has state: key-pair, direct-relations, peer-homes.
- On initialisation:
  - A user-client creates a key-pair if it doesn't exist.
  - Creates empty direct-relations.
  - Must have provided peer-homes addresses on the command line.

A peer is a user-client but also an IPFS client.
- On initialisation:
  - Must have homed-users provided on the command-line.
  - It automatically initialises as the home of itself.

### Actions:

- user-client: submit direct-relations
  1. user-client: Make a request to the peer-clients in peer-homes with the contents of direct-relations and signed user-info documents.
  2. peer-client: Creates the documents in IPFS and then on successful creation updates the IPNS for the user.

- peer-client: batch update context-relations-deps
  Generate a DAG of operations utilising a DAG library.
  For each user, for each context.
  - Add entries for fetching dependent context-relations-deps.
  - Add entries for caching and seeding the fetched items. 
  - Add entries for generating context-relations-deps from the existing deps, this is a pure operation given the data.
  - Add entries for publishing the generated context-relations-deps.
  - Add entries for generating context-relations-deps-index given the context depths, this is a pure operation.
  - Add entries for publishing the generated indexes.
  - Add entries for publishing the newly created context-relations-deps.
  - Add an entry to publish the peer-client's user-info with the updated link to context-relaitons-deps-indexes for all homed users.
  This should operate every 10m, cancelling the previous run if it has gone for too long.
  The final step — publishing user-info with updated context-relations-deps-index links — is the atomic commit of the new state, so a cancelled run leaves the previous consistent state intact.


- user-client: fetch user's context-dependencies-index
  - peer client returns from cache or if necessary fetches

- user-client: fetch direct-relations-collection
  - peer client returns from cache or if necessary fetches

- user-client: collect context
  - client-side operation: builds the rendered view for a given context by
    traversing the CID graph in the context-relations-deps documents.
  - Algorithm: BFS over direct-relations CIDs using two sets — `to-process`
    (initially populated from hop-1 addresses) and `processed` (starts empty).
    For each CID in `to-process` not already in `processed`: fetch the
    direct-relations document, collect its URI and user entries for the context,
    add any new user-relation CIDs to `to-process`, mark the CID as processed.
  - Because documents are content-addressed, the same direct-relations document
    reached via multiple paths (diamond dependencies) has the same CID and is
    naturally deduplicated by the `processed` set. The peer emits CIDs without
    deduplication; the client's visited-set handles it.

### Conflict resolution

When a peer receives a new direct-relations submission, it accepts it if its timestamp-ns is greater than the currently stored document. Latest timestamp wins.


## peer-client

Is written in Golang.

## UI

The user-client is written in typescript with a React UI, no dynamic routing, no NextJS, no state management solution.
Plain React. 

Use a CSS UI library instead of a react components library:
https://picnicss.com

Store state in the URL with serializer and deserializer.

Features:
- Top Bar: Share id as a link.
- Top Bar: Your index -> View User Index, your index
- View: User Index: see a list of their contexts
  - Click on a context and see the contents.
  - View a context-menu on the context with a ">", allows viewing subcontexts as well, limited by numbers, shows weight of each subcontext.
  - Export as map, as vouch, as Link-tree.
- View: User Context:
  - View as map.
  - Filter with a search
  - Can click on users -> User Index
  - Can click on user-context -> User Context
  - Can add an item to your direct-relations in this context.


## Considerations

### Scaling

#### Number of users in the network.

The main consideration here is DHT, which has logarithmic scaling with the node
size *of the DHT network*, not of peers which aren't necessarily DHT nodes but
instead are more likely to be clients.

We can have a smaller network of reliable and trusted nodes to host the DHT.

The initial implementation plans to use IPFS but there is nothing in the
architecture tying us to that, in fact more optimisations could be made by
using a separate DHT network.


#### Number of relations

(fetching direct-relation documents).

By using inductive dependency calculations, each user's peer only has to make
requests corresponding to the number of direct relations for that user, no
matter the depth or number of relations in their network.

Inversely, if a user has many direct relations pointing to it, there will be
many peering its data, as (good citizen) peers are expected to seed data that
it caches. Such popular users can opt for more peers if there are issues with
their servers.

--

Once a user has the documents, for the algorithms we'll need we can scan
through them in a linear fashion, no need to build the graph in memory. Nothing
a modern (or less modern) computer would sneeze at, and should be parallelisable.


#### Number of peer servers

#### User data size

Users watch their own weight.

Peers will likely insist on weight budgets, perhaps there might be paid peers
which allow larger budgets.

When users add a direct relation, they will be able to see the size relating to
that direct relation will have.

If they have too much, they can easily see which direct relation (and number of
hops) is causing bloat, they can make decisions on what they want to keep and
prune.

Conversly, for users that want to have many relates to their context, there are
incentives for keeping their data lean and clean, and using move specific
contexts, all good things.


### High Availability

Users should have more than one peer, it is in their hands to make their data
highly available.


### Misbehaving peers

You might notice that peers have some ability to misbehave and mess with their
users, not update their data, miss important deps in compilations.

It should be possible to cryptographically prove a peer is misbehaving, there
could be an automated system for reporting, blacklists, whitelists.

But trusting peers like this is not so foreign, it is present in Fediverse, Nostr, ATProto.

And if you don't have trust, you can always host your own peer(s).


### Privacy

Privacy is not an explicit goal of this project, this is about people that want
to share and be part of a system of accountability, which necessarily needs
transparency.

Many (most) already are public on many platforms, GitHub, LinkedIn, Instagram,
and if not that many have consistent usernames or emails, maybe websites that
link to their other properties, or photos from friends or family that you post
online. You very likely are more exposed than you think, and this system is
just making the graph explicit - you know exactly what you are making public
and what it is tied to.

That said, this system by-design doesn't require an email or an existing
identifier, you can create as many new identifiers as you like and not have it
tied to any of your existing online or offline presence.

You can have networks of these new identifiers and not know who each person is,
if you are careful enough. You can create fake intermediaries and whole fake
networks, slop and bots on the network aids in privacy.

That said, if you are doing anything requiring strict secrecy or privacy, if
you need to consider state-level threats, the advice is to not use this tool. I
am not quite the right person to ask about what you should use.


## Open questions

- Are there other protocols this should use?

## FAQ

### Why "relate" and not "trust" or "follow"

Why user the noun/verb "relate" and not "trust". I think trust is too strong
and people will complain, you might have a user under a specific context but
not trust them. It also doesn't make sense in a lot of contexts, e.g.
for music or food taste relate is better.

I think "follow" gives the wrong impression and aligns the system with 2000s
social media which it isn't.
