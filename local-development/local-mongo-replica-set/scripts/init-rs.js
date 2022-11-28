rs.initiate(
    {
        _id: "local-rs",
        version: 1,
        members: [
            {_id: 0, host: "server1:27027"},
            {_id: 1, host: "server2:27037"},
        ]
    }
);
