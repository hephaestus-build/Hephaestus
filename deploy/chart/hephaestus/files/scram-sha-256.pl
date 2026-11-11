# Prints the SCRAM-SHA-256 verifier of a password, as libpq's PQencryptPasswordConn computes it, so that no
# plaintext password reaches the PostgreSQL server (docs/admin/helm-chart.md § Database roles):
#
#   DATABASE_OWNER_VERIFIER="$(perl scram-sha-256.pl DATABASE_OWNER_PASSWORD)"
#
# The argument names the environment variable that holds the password, so the password never appears in a command
# line. Each run draws a new 16-byte salt and uses 4096 iterations, PostgreSQL's defaults. A password must be
# printable ASCII: libpq would normalize anything else with SASLprep first, which this script does not.
use strict;
use warnings;
use Digest::SHA qw(hmac_sha256 sha256);
use MIME::Base64 qw(encode_base64);

my $name = shift @ARGV or die "scram-sha-256.pl: name the environment variable that holds the password\n";
my $password = $ENV{$name};
die "scram-sha-256.pl: $name is not set\n" unless defined $password;
die "scram-sha-256.pl: $name is empty\n" if $password eq '';
die "scram-sha-256.pl: $name must be printable ASCII\n" if $password =~ /[^\x20-\x7e]/;

open my $random, '<:raw', '/dev/urandom' or die "scram-sha-256.pl: /dev/urandom: $!\n";
read($random, my $salt, 16) == 16 or die "scram-sha-256.pl: /dev/urandom gave too few bytes\n";
close $random;

my $iterations = 4096;
my $block = hmac_sha256($salt . pack('N', 1), $password);
my $salted = $block;
for (2 .. $iterations) {
    $block = hmac_sha256($block, $password);
    $salted ^= $block;
}
my $stored = sha256(hmac_sha256('Client Key', $salted));
my $server = hmac_sha256('Server Key', $salted);
my $base64 = sub { encode_base64($_[0], '') };
printf "SCRAM-SHA-256\$%d:%s\$%s:%s\n", $iterations, $base64->($salt), $base64->($stored), $base64->($server);
