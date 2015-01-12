#!/bin/sh

FILES=`find . -name "*.java"`
FILES=`grep -r --files-without-match "Copyright [0-9]* Red Hat" $FILES`
COPYRIGHT="/*\n Copyright 2015 Red Hat, Inc. and/or its affiliates.\n\n This file is part of lightblue.\n\n This program is free software: you can redistribute it and/or modify\n it under the terms of the GNU General Public License as published by\n the Free Software Foundation, either version 3 of the License, or\n (at your option) any later version.\n\n This program is distributed in the hope that it will be useful,\n but WITHOUT ANY WARRANTY; without even the implied warranty of\n MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\n GNU General Public License for more details.\n\n You should have received a copy of the GNU General Public License\n along with this program.  If not, see <http://www.gnu.org/licenses/>.\n */"

# add copyright to ALL java files
sed -u -i "s#^package#$COPYRIGHT\npackage#" $FILES

