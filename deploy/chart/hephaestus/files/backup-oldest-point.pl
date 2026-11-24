# The start of the oldest retained backup is a conservative recovery point.
use strict;
use warnings;
use JSON::PP;

local $/;
my $stanzas = decode_json(<STDIN>);
die "Expected pgBackRest info for one available stanza\n"
    unless ref $stanzas eq 'ARRAY' && @$stanzas == 1 && ref $stanzas->[0] eq 'HASH';
my $stanza = $stanzas->[0];
die "Expected an available Hephaestus stanza\n"
    unless ($stanza->{name} // '') eq 'hephaestus' && ref $stanza->{status} eq 'HASH'
        && !ref($stanza->{status}->{code}) && ($stanza->{status}->{code} // '') eq '0';
my $backups = $stanza->{backup};
die "No retained pgBackRest backup\n" unless ref $backups eq 'ARRAY' && @$backups;

my $oldest;
for my $backup (@$backups) {
    my $start = $backup->{timestamp}->{start};
    die "Invalid pgBackRest backup start time\n"
        unless defined $start && $start =~ /\A[1-9][0-9]{0,11}\z/;
    $oldest = $start if !defined $oldest || $start < $oldest;
}
print "$oldest\n";
